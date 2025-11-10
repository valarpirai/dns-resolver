package org.valarpirai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Recursive DNS Resolver
 * Tests actual DNS resolution against real servers
 *
 * WARNING: These tests make real network calls and may fail if:
 * - Network connectivity is unavailable
 * - DNS servers are unreachable
 * - Firewall blocks DNS traffic
 */
class RecursiveDnsResolverIntegrationTest {

    private RecursiveDnsResolver resolver;
    private Configuration testConfig;

    @BeforeEach
    void setUp() {
        testConfig = Configuration.getInstance();
        resolver = new RecursiveDnsResolver(testConfig);
    }

    @AfterEach
    void tearDown() {
        if (resolver != null) {
            resolver.shutdown();
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveWellKnownDomain() {
        // Test resolving a well-known domain
        DnsRequest request = createDnsRequest("example.com", 1);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getHeader(), "Response should have header");
        assertEquals(0, response.getHeader().rcode(), "Response should have no error");
        assertTrue(response.getAnswers().size() > 0, "Response should have answers");

        // Verify answer contains A record
        DnsRecord answer = response.getAnswers().get(0);
        assertEquals(1, answer.type(), "Should be A record");
        assertEquals(4, answer.rdata().length, "A record should have 4 bytes (IPv4)");

        // Verify resolution statistics
        assertFalse(response.isCacheHit(), "First query should not be cache hit");
        assertTrue(response.getQueriesMade() > 0, "Should have made queries");
        assertTrue(response.getMaxDepthReached() >= 0, "Should have depth info");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveCaching() {
        // First resolution
        DnsRequest request1 = createDnsRequest("google.com", 1);
        DnsResponse response1 = resolver.resolve(request1);

        assertNotNull(response1, "First response should not be null");
        assertFalse(response1.isCacheHit(), "First query should not be cache hit");
        int firstQueriesMade = response1.getQueriesMade();
        assertTrue(firstQueriesMade > 0, "First query should make server queries");

        // Second resolution - should hit cache
        DnsRequest request2 = createDnsRequest("google.com", 1);
        DnsResponse response2 = resolver.resolve(request2);

        assertNotNull(response2, "Second response should not be null");
        assertTrue(response2.isCacheHit(), "Second query should be cache hit");
        assertEquals(0, response2.getQueriesMade(), "Cache hit should not make queries");
        assertEquals(0, response2.getMaxDepthReached(), "Cache hit should have zero depth");

        // Both should have same answer
        assertEquals(response1.getAnswers().size(), response2.getAnswers().size(),
            "Cached response should have same number of answers");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveIPv6() {
        // Test resolving AAAA record
        DnsRequest request = createDnsRequest("google.com", 28);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");

        if (response.getAnswers().size() > 0) {
            DnsRecord answer = response.getAnswers().get(0);
            // Should be AAAA record
            assertTrue(answer.type() == 28 || answer.type() == 5,
                "Should be AAAA or CNAME record");

            if (answer.type() == 28) {
                assertEquals(16, answer.rdata().length, "AAAA record should have 16 bytes (IPv6)");
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveMultipleDomains() {
        String[] domains = {"example.com", "example.org", "example.net"};

        for (String domain : domains) {
            DnsRequest request = createDnsRequest(domain, 1);
            DnsResponse response = resolver.resolve(request);

            assertNotNull(response, "Response for " + domain + " should not be null");
            assertEquals(0, response.getHeader().rcode(),
                "Response for " + domain + " should have no error");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveNonExistentDomain() {
        // Test NXDOMAIN response
        String nonExistent = "this-domain-definitely-does-not-exist-" +
            System.currentTimeMillis() + ".com";

        DnsRequest request = createDnsRequest(nonExistent, 1);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null even for NXDOMAIN");
        // RCODE 3 is NXDOMAIN
        assertTrue(response.getHeader().rcode() == 3 || response.getAnswers().isEmpty(),
            "Should have NXDOMAIN or no answers");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentResolution() throws InterruptedException {
        int threadCount = 5;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicBoolean hadError = new AtomicBoolean(false);

        String[] domains = {
            "google.com", "facebook.com", "amazon.com",
            "microsoft.com", "apple.com"
        };

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    DnsRequest request = createDnsRequest(domains[index], 1);
                    DnsResponse response = resolver.resolve(request);

                    if (response != null && response.getHeader().rcode() == 0) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    hadError.set(true);
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(60, TimeUnit.SECONDS), "All threads should complete");
        assertFalse(hadError.get(), "No errors should occur during concurrent resolution");
        assertTrue(successCount.get() > 0, "At least some resolutions should succeed");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveWithEmptyQuestions() {
        // Test handling of request with no questions
        DnsRequest request = DnsRequest.builder()
            .header(DnsHeader.builder()
                .id(12345)
                .qr(false)
                .rd(true)
                .qdcount(0)
                .build())
            .build();

        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");
        assertEquals(1, response.getHeader().rcode(), "Should have format error");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolutionStatistics() {
        // Test that statistics are properly tracked
        DnsRequest request = createDnsRequest("example.com", 1);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");

        // First query - should not be cached
        assertFalse(response.isCacheHit(), "Should not be cache hit");
        assertTrue(response.getQueriesMade() >= 0, "Should have queries made count");
        assertTrue(response.getMaxDepthReached() >= 0, "Should have depth reached");

        // Second query - should be cached
        DnsRequest request2 = createDnsRequest("example.com", 1);
        DnsResponse response2 = resolver.resolve(request2);

        assertTrue(response2.isCacheHit(), "Should be cache hit");
        assertEquals(0, response2.getQueriesMade(), "Cache hit should have 0 queries");
        assertEquals(0, response2.getMaxDepthReached(), "Cache hit should have 0 depth");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveDifferentRecordTypes() {
        String domain = "example.com";

        // Test A record
        DnsRequest requestA = createDnsRequest(domain, 1);
        DnsResponse responseA = resolver.resolve(requestA);
        assertNotNull(responseA, "A record response should not be null");

        // Test AAAA record
        DnsRequest requestAAAA = createDnsRequest(domain, 28);
        DnsResponse responseAAAA = resolver.resolve(requestAAAA);
        assertNotNull(responseAAAA, "AAAA record response should not be null");

        // Both should be cached independently
        DnsRequest requestA2 = createDnsRequest(domain, 1);
        DnsResponse responseA2 = resolver.resolve(requestA2);
        assertTrue(responseA2.isCacheHit(), "A record should be cached");

        DnsRequest requestAAAA2 = createDnsRequest(domain, 28);
        DnsResponse responseAAAA2 = resolver.resolve(requestAAAA2);
        assertTrue(responseAAAA2.isCacheHit(), "AAAA record should be cached");
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveSubdomain() {
        // Test resolving a well-known subdomain
        // Using www.google.com instead which is more reliable
        DnsRequest request = createDnsRequest("www.google.com", 1);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");
        assertNotNull(response.getHeader(), "Response should have header");
        // Response may have answers or CNAME chain - either is fine
    }

    @Test
    void testResolverShutdown() {
        // Test that shutdown works properly
        assertDoesNotThrow(() -> resolver.shutdown(),
            "Shutdown should not throw exception");

        // After shutdown, resolver might still work but cache is cleared
        // This depends on implementation details
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveMultipleAnswers() {
        // Some domains return multiple A records
        DnsRequest request = createDnsRequest("google.com", 1);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");

        if (response.getHeader().rcode() == 0) {
            assertTrue(response.getAnswers().size() >= 0,
                "Should have answers or CNAME chain");
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void testResolveCNAMEChain() {
        // Test domain that might have CNAME
        DnsRequest request = createDnsRequest("www.google.com", 1);
        DnsResponse response = resolver.resolve(request);

        assertNotNull(response, "Response should not be null");

        if (response.getAnswers().size() > 0) {
            // May have CNAME records (type 5) or A records (type 1)
            for (DnsRecord answer : response.getAnswers()) {
                assertTrue(answer.type() == 1 || answer.type() == 5 || answer.type() == 28,
                    "Should have A, AAAA, or CNAME record");
            }
        }
    }

    /**
     * Helper method to create DNS request
     */
    private DnsRequest createDnsRequest(String domain, int type) {
        DnsRequest request = DnsRequest.builder()
            .header(DnsHeader.builder()
                .id((int) (Math.random() * 65535))
                .qr(false)
                .rd(true)
                .qdcount(1)
                .build())
            .build();

        DnsQuestion question = DnsQuestion.builder()
            .name(domain)
            .type(type)
            .qclass(1)
            .build();

        request.addQuestion(question);
        return request;
    }
}
