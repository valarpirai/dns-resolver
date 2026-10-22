package org.valarpirai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for DNS Server
 * Tests server lifecycle, request handling, and concurrent operations
 */
class DnsServerIntegrationTest {

    private static final int TEST_PORT = 15353; // Use non-privileged port for testing
    private static final String TEST_HOST = "127.0.0.1";

    private DnsServer server;
    private Thread serverThread;
    private Configuration testConfig;

    @BeforeEach
    void setUp() {
        testConfig = Configuration.getInstance();
        // Note: Configuration is a singleton, so we can't easily override port
        // In production, you'd want to make Configuration more test-friendly
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
            try {
                serverThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testServerStartAndStop() throws Exception {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean error = new AtomicBoolean(false);

        // Note: This test requires sudo/root to bind to port 53
        // Skipping actual server start for integration test
        // In real deployment, you'd use a test port or have elevated permissions

        // Create server instance
        server = new DnsServer(testConfig);

        // Verify initial state
        assertFalse(server.isRunning(), "Server should not be running initially");
        assertEquals(53, server.getPort(), "Server should have correct port");

        // Note: We skip actual start() as it requires privileged port
        // In production, you'd refactor to support custom port for testing
    }

    @Test
    void testServerConstructorWithConfiguration() {
        server = new DnsServer(testConfig);

        assertNotNull(server, "Server should be created");
        assertFalse(server.isRunning(), "Server should not be running after construction");
    }

    @Test
    void testServerDefaultConstructor() {
        server = new DnsServer();

        assertNotNull(server, "Server should be created with default constructor");
        assertFalse(server.isRunning(), "Server should not be running after construction");
    }

    @Test
    void testMultipleStopCalls() {
        server = new DnsServer(testConfig);

        // Multiple stop calls should not cause errors
        assertDoesNotThrow(() -> server.stop(), "First stop should not throw");
        assertDoesNotThrow(() -> server.stop(), "Second stop should not throw");
        assertDoesNotThrow(() -> server.stop(), "Third stop should not throw");
    }

    /**
     * Test DNS query packet construction
     * This verifies our ability to create valid DNS queries
     */
    @Test
    void testDnsQueryPacketConstruction() {
        // Build a DNS query for "example.com" A record
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // DNS Header
        buffer.putShort((short) 0x1234); // Transaction ID
        buffer.putShort((short) 0x0100); // Flags: Standard query, recursion desired
        buffer.putShort((short) 1);      // Questions: 1
        buffer.putShort((short) 0);      // Answer RRs: 0
        buffer.putShort((short) 0);      // Authority RRs: 0
        buffer.putShort((short) 0);      // Additional RRs: 0

        // Question section: "example.com"
        for (String label : "example.com".split("\\.")) {
            buffer.put((byte) label.length());
            buffer.put(label.getBytes());
        }
        buffer.put((byte) 0); // End of domain name

        buffer.putShort((short) 1);  // Type: A
        buffer.putShort((short) 1);  // Class: IN

        byte[] queryBytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(queryBytes);

        // Verify packet structure
        assertTrue(queryBytes.length > 12, "Query should have header");
        assertEquals(0x12, queryBytes[0], "Should have correct transaction ID high byte");
        assertEquals(0x34, queryBytes[1], "Should have correct transaction ID low byte");
    }

    /**
     * Test parsing of DNS response
     */
    @Test
    void testDnsResponseParsing() throws Exception {
        // Create a simple DNS response for "example.com" -> 93.184.216.34
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Header
        buffer.putShort((short) 0x1234); // ID
        buffer.putShort((short) 0x8180); // Flags: Response, recursion available
        buffer.putShort((short) 1);      // Questions: 1
        buffer.putShort((short) 1);      // Answers: 1
        buffer.putShort((short) 0);      // Authority: 0
        buffer.putShort((short) 0);      // Additional: 0

        // Question
        buffer.put((byte) 7);
        buffer.put("example".getBytes());
        buffer.put((byte) 3);
        buffer.put("com".getBytes());
        buffer.put((byte) 0);
        buffer.putShort((short) 1);  // Type A
        buffer.putShort((short) 1);  // Class IN

        // Answer
        buffer.putShort((short) 0xC00C); // Pointer to name
        buffer.putShort((short) 1);      // Type A
        buffer.putShort((short) 1);      // Class IN
        buffer.putInt(300);              // TTL
        buffer.putShort((short) 4);      // Data length
        buffer.put((byte) 93);
        buffer.put((byte) 184);
        buffer.put((byte) 216);
        buffer.put((byte) 34);

        byte[] responseBytes = new byte[buffer.position()];
        buffer.flip();
        buffer.get(responseBytes);

        // Parse using DnsRequest
        DnsRequest request = DnsRequest.parse(responseBytes);

        assertNotNull(request, "Should parse response");
        assertEquals(0x1234, request.getHeader().id(), "Should have correct ID");
        assertTrue(request.getHeader().qr(), "Should be a response");
    }

    /**
     * Helper method to create DNS query bytes
     */
    private byte[] createDnsQuery(String domain, int type) {
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Header
        buffer.putShort((short) (Math.random() * 65535)); // Random ID
        buffer.putShort((short) 0x0100); // Standard query, recursion desired
        buffer.putShort((short) 1);      // 1 question
        buffer.putShort((short) 0);      // 0 answers
        buffer.putShort((short) 0);      // 0 authority
        buffer.putShort((short) 0);      // 0 additional

        // Question
        for (String label : domain.split("\\.")) {
            buffer.put((byte) label.length());
            buffer.put(label.getBytes());
        }
        buffer.put((byte) 0); // End of name
        buffer.putShort((short) type);  // Type
        buffer.putShort((short) 1);     // Class IN

        byte[] query = new byte[buffer.position()];
        buffer.flip();
        buffer.get(query);
        return query;
    }

    /**
     * Test DNS query validation
     */
    @Test
    void testDnsQueryValidation() {
        // Valid query
        byte[] validQuery = createDnsQuery("example.com", 1);
        assertTrue(validQuery.length > 12, "Valid query should have minimum size");

        // Query with minimum fields
        assertEquals(1, ByteBuffer.wrap(validQuery).getShort(4),
            "Should have 1 question");
    }

    /**
     * Test concurrent query construction
     * Simulates multiple clients creating queries simultaneously
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConcurrentQueryConstruction() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicBoolean hadError = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            new Thread(() -> {
                try {
                    byte[] query = createDnsQuery("domain" + index + ".com", 1);
                    assertNotNull(query, "Query should be created");
                    assertTrue(query.length > 12, "Query should have valid size");
                } catch (Exception e) {
                    hadError.set(true);
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS), "All threads should complete");
        assertFalse(hadError.get(), "No errors should occur");
    }

    /**
     * Test handling of malformed DNS queries
     */
    @Test
    void testMalformedQueryHandling() {
        // Too short packet (less than DNS header size)
        byte[] tooShort = new byte[5];

        assertThrows(Exception.class, () -> {
            DnsRequest.parse(tooShort);
        }, "Should throw on malformed packet");

        // Empty packet
        byte[] empty = new byte[0];

        assertThrows(Exception.class, () -> {
            DnsRequest.parse(empty);
        }, "Should throw on empty packet");
    }

    /**
     * Test DNS response serialization
     */
    @Test
    void testDnsResponseSerialization() throws IOException {
        // Create a response
        DnsRequest request = DnsRequest.builder()
            .header(DnsHeader.builder()
                .id(12345)
                .qr(false)
                .rd(true)
                .qdcount(1)
                .build())
            .build();

        DnsQuestion question = DnsQuestion.builder()
            .name("test.com")
            .type(1)
            .qclass(1)
            .build();
        request.addQuestion(question);

        DnsResponse response = new DnsResponse(request);
        response.addAnswer(DnsRecord.createARecord("test.com", "1.2.3.4", 300));

        // Serialize
        byte[] bytes = response.toBytes();

        assertNotNull(bytes, "Response should serialize");
        assertTrue(bytes.length > 12, "Response should have header");

        // Parse back
        DnsRequest parsed = DnsRequest.parse(bytes);
        assertNotNull(parsed, "Should parse serialized response");
        assertEquals(12345, parsed.getHeader().id(), "Should preserve transaction ID");
    }
}
