package org.valarpirai.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Remembers which nameservers are authoritative for a zone.
 * Lets a lookup skip the root and TLD hops when a parent zone's servers are already known.
 */
public class DelegationCache {
    private static final int MAX_ZONES = 10_000;

    private final Cache<String, Delegation> zones = Caffeine.newBuilder()
            .maximumSize(MAX_ZONES)
            .expireAfter(new Expiry<String, Delegation>() {
                @Override
                public long expireAfterCreate(String key, Delegation value, long currentTime) {
                    return TimeUnit.SECONDS.toNanos(value.ttl());
                }

                @Override
                public long expireAfterUpdate(String key, Delegation value, long currentTime, long currentDuration) {
                    return TimeUnit.SECONDS.toNanos(value.ttl());
                }

                @Override
                public long expireAfterRead(String key, Delegation value, long currentTime, long currentDuration) {
                    return currentDuration;
                }
            })
            .build();

    /**
     * Store the nameserver addresses for a zone.
     * Ignored when the zone is not an ancestor of the name being resolved, so a server cannot
     * plant delegations for unrelated zones.
     *
     * @param zone       the zone the servers are authoritative for
     * @param servers    IPv4 addresses of the zone's nameservers
     * @param ttl        seconds the delegation stays valid
     * @param resolvedName the name whose lookup produced this delegation
     */
    public void put(String zone, List<String> servers, int ttl, String resolvedName) {
        String z = zone.toLowerCase();
        if (z.isEmpty() || servers.isEmpty() || ttl <= 0 || !isWithin(resolvedName.toLowerCase(), z)) {
            return;
        }
        zones.put(z, new Delegation(List.copyOf(servers), ttl));
    }

    /**
     * Find the servers of the closest enclosing zone of a name.
     *
     * @param name the name about to be resolved
     * @return the nameserver addresses, or null when no enclosing zone is cached
     */
    public List<String> closest(String name) {
        String candidate = name.toLowerCase();
        while (!candidate.isEmpty()) {
            Delegation d = zones.getIfPresent(candidate);
            if (d != null) return d.servers();
            int dot = candidate.indexOf('.');
            candidate = dot < 0 ? "" : candidate.substring(dot + 1);
        }
        return null;
    }

    public void clear() {
        zones.invalidateAll();
    }

    private static boolean isWithin(String name, String zone) {
        return name.equals(zone) || name.endsWith("." + zone);
    }

    private record Delegation(List<String> servers, int ttl) {}
}
