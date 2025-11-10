package org.valarpirai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import lombok.extern.java.Log;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * DNS Response Cache using Caffeine
 * Implements TTL-based expiration from DNS responses
 */
@Log
public class DnsCache {
    private static final Logger LOGGER = Logger.getLogger(DnsCache.class.getName());

    private final Cache<CacheKey, CacheEntry> cache;
    private final Configuration config;

    public DnsCache(Configuration config) {
        this.config = config;

        // Build cache with custom TTL expiration
        this.cache = Caffeine.newBuilder()
                .expireAfter(new Expiry<CacheKey, CacheEntry>() {
                    @Override
                    public long expireAfterCreate(CacheKey key, CacheEntry value, long currentTime) {
                        // Use TTL from DNS response (in seconds, convert to nanoseconds)
                        return TimeUnit.SECONDS.toNanos(value.getTtl());
                    }

                    @Override
                    public long expireAfterUpdate(CacheKey key, CacheEntry value,
                                                 long currentTime, long currentDuration) {
                        return currentDuration;
                    }

                    @Override
                    public long expireAfterRead(CacheKey key, CacheEntry value,
                                               long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .maximumSize(10000)
                .recordStats()
                .build();

        LOGGER.info("DNS Cache initialized with TTL-based expiration");
    }

    /**
     * Get cached DNS response
     */
    public List<DnsRecord> get(String name, int type) {
        CacheKey key = new CacheKey(name.toLowerCase(), type);
        CacheEntry entry = cache.getIfPresent(key);

        if (entry != null) {
            LOGGER.info(String.format("Cache HIT: %s %s (TTL: %ds)",
                    name, getTypeName(type), entry.getTtl()));
            return entry.getRecords();
        }

        LOGGER.info(String.format("Cache MISS: %s %s", name, getTypeName(type)));
        return null;
    }

    /**
     * Put DNS response in cache
     */
    public void put(String name, int type, List<DnsRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        // Find minimum TTL from all records
        int minTtl = records.stream()
                .mapToInt(DnsRecord::ttl)
                .min()
                .orElse(300); // Default 5 minutes

        // Don't cache records with very short TTL
        if (minTtl < 10) {
            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("Skipping cache: %s %s (TTL too short: %ds)",
                        name, getTypeName(type), minTtl));
            }
            return;
        }

        CacheKey key = new CacheKey(name.toLowerCase(), type);
        CacheEntry entry = new CacheEntry(records, minTtl);
        cache.put(key, entry);

        LOGGER.info(String.format("Cached: %s %s (%d record(s), TTL: %ds)",
                name, getTypeName(type), records.size(), minTtl));
    }

    /**
     * Clear all cache entries
     */
    public void clear() {
        cache.invalidateAll();
        LOGGER.info("Cache cleared");
    }

    /**
     * Get cache statistics
     */
    public void printStats() {
        var stats = cache.stats();
        LOGGER.info(String.format("Cache Stats - Hits: %d, Misses: %d, Size: %d, Hit Rate: %.2f%%",
                stats.hitCount(),
                stats.missCount(),
                cache.estimatedSize(),
                stats.hitRate() * 100));
    }

    /**
     * Cache key combining domain name and query type
     */
    private static class CacheKey {
        private final String name;
        private final int type;

        CacheKey(String name, int type) {
            this.name = name;
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return type == cacheKey.type && name.equals(cacheKey.name);
        }

        @Override
        public int hashCode() {
            return 31 * name.hashCode() + type;
        }
    }

    /**
     * Cache entry storing records and TTL
     */
    private static class CacheEntry {
        private final List<DnsRecord> records;
        private final int ttl;

        CacheEntry(List<DnsRecord> records, int ttl) {
            this.records = records;
            this.ttl = ttl;
        }

        public List<DnsRecord> getRecords() {
            return records;
        }

        public int getTtl() {
            return ttl;
        }
    }

    /**
     * Get DNS type name
     */
    private String getTypeName(int type) {
        return switch (type) {
            case 1 -> "A";
            case 2 -> "NS";
            case 5 -> "CNAME";
            case 6 -> "SOA";
            case 12 -> "PTR";
            case 15 -> "MX";
            case 16 -> "TXT";
            case 28 -> "AAAA";
            default -> "TYPE_" + type;
        };
    }
}
