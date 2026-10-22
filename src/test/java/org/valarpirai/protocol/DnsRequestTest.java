package org.valarpirai.protocol;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class DnsRequestTest {

    @Test
    void testBuilder() {
        DnsHeader header = DnsHeader.builder()
                .id(12345)
                .qr(false)
                .rd(true)
                .qdcount(1)
                .build();

        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        DnsRequest request = DnsRequest.builder()
                .header(header)
                .build();
        request.addQuestion(question);

        assertEquals(header, request.getHeader());
        assertEquals(1, request.getQuestions().size());
        assertEquals(question, request.getQuestions().get(0));
    }

    @Test
    void testParse_ValidQuery() {
        // Create a simple DNS query for "example.com" A record
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Header
        buffer.putShort((short) 12345);  // ID
        buffer.put((byte) 0x01);  // Flags: RD=1
        buffer.put((byte) 0x00);  // Flags
        buffer.putShort((short) 1);  // QDCOUNT=1
        buffer.putShort((short) 0);  // ANCOUNT=0
        buffer.putShort((short) 0);  // NSCOUNT=0
        buffer.putShort((short) 0);  // ARCOUNT=0

        // Question: example.com
        buffer.put((byte) 7);  // Length of "example"
        buffer.put("example".getBytes());
        buffer.put((byte) 3);  // Length of "com"
        buffer.put("com".getBytes());
        buffer.put((byte) 0);  // End of domain name

        buffer.putShort((short) 1);  // QTYPE=A
        buffer.putShort((short) 1);  // QCLASS=IN

        byte[] data = new byte[buffer.position()];
        buffer.flip();
        buffer.get(data);

        // Parse the request
        DnsRequest request = DnsRequest.parse(data);

        assertNotNull(request);
        assertEquals(12345, request.getHeader().id());
        assertTrue(request.getHeader().rd());
        assertFalse(request.getHeader().qr());
        assertEquals(1, request.getQuestions().size());

        DnsQuestion question = request.getQuestions().get(0);
        assertEquals("example.com", question.name());
        assertEquals(1, question.type());
        assertEquals(1, question.qclass());
    }

    @Test
    void testParse_InvalidQuery_TooShort() {
        byte[] data = new byte[10];  // Less than 12 bytes (header size)

        assertThrows(IllegalArgumentException.class, () ->
                DnsRequest.parse(data));
    }

    @Test
    void testParse_NullData() {
        assertThrows(IllegalArgumentException.class, () ->
                DnsRequest.parse(null));
    }

    @Test
    void testParse_MultipleQuestions() {
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Header
        buffer.putShort((short) 54321);
        buffer.put((byte) 0x01);  // RD=1
        buffer.put((byte) 0x00);
        buffer.putShort((short) 2);  // QDCOUNT=2
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);

        // Question 1: google.com A
        buffer.put((byte) 6);
        buffer.put("google".getBytes());
        buffer.put((byte) 3);
        buffer.put("com".getBytes());
        buffer.put((byte) 0);
        buffer.putShort((short) 1);  // A
        buffer.putShort((short) 1);  // IN

        // Question 2: yahoo.com AAAA
        buffer.put((byte) 5);
        buffer.put("yahoo".getBytes());
        buffer.put((byte) 3);
        buffer.put("com".getBytes());
        buffer.put((byte) 0);
        buffer.putShort((short) 28);  // AAAA
        buffer.putShort((short) 1);   // IN

        byte[] data = new byte[buffer.position()];
        buffer.flip();
        buffer.get(data);

        DnsRequest request = DnsRequest.parse(data);

        assertNotNull(request);
        assertEquals(2, request.getQuestions().size());

        assertEquals("google.com", request.getQuestions().get(0).name());
        assertEquals(1, request.getQuestions().get(0).type());

        assertEquals("yahoo.com", request.getQuestions().get(1).name());
        assertEquals(28, request.getQuestions().get(1).type());
    }

    @Test
    void testAddQuestion() {
        DnsRequest request = DnsRequest.builder().build();

        DnsQuestion q1 = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        DnsQuestion q2 = DnsQuestion.builder()
                .name("google.com")
                .type(28)
                .qclass(1)
                .build();

        request.addQuestion(q1);
        request.addQuestion(q2);

        assertEquals(2, request.getQuestions().size());
        assertEquals(q1, request.getQuestions().get(0));
        assertEquals(q2, request.getQuestions().get(1));
    }

    @Test
    void testRawData() {
        byte[] originalData = new byte[]{1, 2, 3, 4, 5};

        DnsRequest request = DnsRequest.builder()
                .rawData(originalData)
                .build();

        assertArrayEquals(originalData, request.getRawData());
    }

    @Test
    void testToString() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .build();

        DnsRequest request = DnsRequest.builder()
                .header(header)
                .build();

        String str = request.toString();
        assertNotNull(str);
        assertTrue(str.contains("DnsRequest"));
        assertTrue(str.contains("header"));
        assertTrue(str.contains("questions"));
    }
}
