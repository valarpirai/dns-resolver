package org.valarpirai.cache;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DelegationCacheTest {

    @Test
    void closestEnclosingZoneWins() {
        DelegationCache cache = new DelegationCache();
        cache.put("com", List.of("1.1.1.1"), 300, "www.example.com");
        cache.put("example.com", List.of("2.2.2.2"), 300, "www.example.com");

        assertEquals(List.of("2.2.2.2"), cache.closest("www.example.com"));
        assertEquals(List.of("2.2.2.2"), cache.closest("example.com"));
        assertEquals(List.of("1.1.1.1"), cache.closest("other.com"));
    }

    @Test
    void lookupIsCaseInsensitive() {
        DelegationCache cache = new DelegationCache();
        cache.put("Example.COM", List.of("2.2.2.2"), 300, "WWW.example.com");

        assertEquals(List.of("2.2.2.2"), cache.closest("www.EXAMPLE.com"));
    }

    @Test
    void unrelatedNamesMiss() {
        DelegationCache cache = new DelegationCache();
        cache.put("example.com", List.of("2.2.2.2"), 300, "www.example.com");

        assertNull(cache.closest("example.org"));
        assertNull(cache.closest("notexample.com"));
    }

    @Test
    void rejectsZoneOutsideResolvedName() {
        DelegationCache cache = new DelegationCache();
        cache.put("victim.com", List.of("6.6.6.6"), 300, "www.example.org");

        assertNull(cache.closest("victim.com"));
    }

    @Test
    void ignoresEmptyServersZeroTtlAndRootZone() {
        DelegationCache cache = new DelegationCache();
        cache.put("example.com", List.of(), 300, "example.com");
        cache.put("example.com", List.of("2.2.2.2"), 0, "example.com");
        cache.put("", List.of("3.3.3.3"), 300, "example.com");

        assertNull(cache.closest("example.com"));
    }

    @Test
    void clearRemovesEntries() {
        DelegationCache cache = new DelegationCache();
        cache.put("example.com", List.of("2.2.2.2"), 300, "example.com");
        cache.clear();

        assertNull(cache.closest("example.com"));
    }
}
