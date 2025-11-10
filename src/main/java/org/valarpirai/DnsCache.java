package org.valarpirai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.RemovalCause;
import lombok.extern.java.Log;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final ScheduledExecutorService statsScheduler;
    private final int maxEntries;
    private final long maxMemoryBytes;

    public DnsCache(Configuration config) {
        this.config = config;

        // Read cache configuration
        this.maxEntries = config.getInt("cache.max.entries", 10000);
        this.maxMemoryBytes = config.getLong("cache.max.memory", 10485760L); // 10MB default
        int statsInterval = config.getInt("cache.stats.interval", 300); // 5 minutes default

        // Build cache with custom TTL expiration and memory-based eviction
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
                .maximumWeight(maxMemoryBytes)
                .weigher((CacheKey key, CacheEntry value) -> {
                    // Calculate approximate memory usage
                    int keySize = key.name.length() * 2 + 4; // String chars + type int
                    int entrySize = 4; // ttl int
                    int recordsSize = value.records.stream()
                            .mapToInt(record ->
                                record.name().length() * 2 + // name string
                                4 + 4 + 4 + 4 + // type, class, ttl, rdlength (ints)
                                (record.rdata() != null ? record.rdata().length : 0) // rdata bytes
                            )
                            .sum();
                    return keySize + entrySize + recordsSize;
                })
                .removalListener((CacheKey key, CacheEntry value, RemovalCause cause) -> {
                    if (config.isDebugEnabled() && cause == RemovalCause.SIZE) {
                        LOGGER.info(String.format("Cache eviction (size limit): %s %s",
                                key.name, getTypeName(key.type)));
                    }
                })
                .recordStats()
                .build();

        LOGGER.info(String.format("DNS Cache initialized - Max entries: %d, Max memory: %d bytes (%.2f MB), TTL-based expiration enabled",
                maxEntries, maxMemoryBytes, maxMemoryBytes / 1024.0 / 1024.0));

        // Schedule periodic stats logging if enabled
        if (statsInterval > 0) {
            this.statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "dns-cache-stats");
                t.setDaemon(true);
                return t;
            });
            this.statsScheduler.scheduleAtFixedRate(
                    this::printStats,
                    statsInterval,
                    statsInterval,
                    TimeUnit.SECONDS
            );
            LOGGER.info(String.format("Cache statistics will be logged every %d seconds", statsInterval));
        } else {
            this.statsScheduler = null;
            LOGGER.info("Periodic cache statistics logging is disabled");
        }
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
        long estimatedSize = cache.estimatedSize();
        long evictionCount = stats.evictionCount();

        LOGGER.info(String.format("Cache Stats - Hits: %d, Misses: %d, Evictions: %d, Size: %d entries, Hit Rate: %.2f%%, Max Memory: %.2f MB",
                stats.hitCount(),
                stats.missCount(),
                evictionCount,
                estimatedSize,
                stats.hitRate() * 100,
                maxMemoryBytes / 1024.0 / 1024.0));
    }

    /**
     * Shutdown the cache and cleanup resources
     */
    public void shutdown() {
        if (statsScheduler != null) {
            statsScheduler.shutdown();
            try {
                if (!statsScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    statsScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                statsScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        cache.invalidateAll();
        LOGGER.info("DNS Cache shutdown completed");
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
