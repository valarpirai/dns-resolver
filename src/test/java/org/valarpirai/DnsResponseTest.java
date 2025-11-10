package org.valarpirai;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class DnsResponseTest {

    @Test
    void testBuilder() {
        DnsHeader header = DnsHeader.builder()
                .id(12345)
                .qr(true)
                .rcode(0)
                .build();

        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        DnsRecord answer = DnsRecord.createARecord("example.com", "93.184.216.34", 3600);

        DnsResponse response = DnsResponse.builder()
                .header(header)
                .build();
        response.getQuestions().add(question);
        response.addAnswer(answer);

        // Verify header ID (addAnswer modifies header with new ancount)
        assertEquals(12345, response.getHeader().id());
        assertTrue(response.getHeader().qr());
        assertEquals(1, response.getHeader().ancount());  // Updated by addAnswer
        assertEquals(1, response.getQuestions().size());
        assertEquals(1, response.getAnswers().size());
        assertEquals(answer, response.getAnswers().get(0));
    }

    @Test
    void testConstructorFromRequest() {
        DnsHeader reqHeader = DnsHeader.builder()
                .id(100)
                .qr(false)
                .opcode(0)
                .rd(true)
                .qdcount(1)
                .build();

        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        DnsRequest request = DnsRequest.builder()
                .header(reqHeader)
                .build();
        request.addQuestion(question);

        DnsResponse response = new DnsResponse(request);

        // Verify header was copied and modified
        assertEquals(100, response.getHeader().id());
        assertTrue(response.getHeader().qr());  // Set to true for response
        assertEquals(0, response.getHeader().opcode());
        assertTrue(response.getHeader().rd());

        // Verify questions were copied
        assertEquals(1, response.getQuestions().size());
        assertEquals("example.com", response.getQuestions().get(0).name());
    }

    @Test
    void testAddAnswer() {
        DnsResponse response = DnsResponse.builder().build();

        DnsRecord answer1 = DnsRecord.createARecord("example.com", "1.2.3.4", 3600);
        DnsRecord answer2 = DnsRecord.createARecord("example.com", "5.6.7.8", 3600);

        response.addAnswer(answer1);
        assertEquals(1, response.getAnswers().size());
        assertEquals(1, response.getHeader().ancount());

        response.addAnswer(answer2);
        assertEquals(2, response.getAnswers().size());
        assertEquals(2, response.getHeader().ancount());
    }

    @Test
    void testToBytes_SimpleResponse() {
        // Create request
        DnsHeader reqHeader = DnsHeader.builder()
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
                .header(reqHeader)
                .build();
        request.addQuestion(question);

        // Create response
        DnsResponse response = new DnsResponse(request);
        DnsRecord answer = DnsRecord.createARecord("example.com", "93.184.216.34", 3600);
        response.addAnswer(answer);

        // Convert to bytes
        byte[] bytes = response.toBytes();

        assertNotNull(bytes);
        assertTrue(bytes.length > 12);  // At least header size

        // Parse header from bytes
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int id = buffer.getShort() & 0xFFFF;
        int flags1 = buffer.get() & 0xFF;
        int flags2 = buffer.get() & 0xFF;
        int qdcount = buffer.getShort() & 0xFFFF;
        int ancount = buffer.getShort() & 0xFFFF;

        assertEquals(12345, id);
        assertTrue((flags1 & 0x80) != 0);  // QR=1 (response)
        assertTrue((flags1 & 0x01) != 0);  // RD=1
        assertEquals(1, qdcount);
        assertEquals(1, ancount);
    }

    @Test
    void testToBytes_MultipleAnswers() {
        DnsHeader header = DnsHeader.builder()
                .id(999)
                .qr(true)
                .build();

        DnsQuestion question = DnsQuestion.builder()
                .name("google.com")
                .type(1)
                .qclass(1)
                .build();

        DnsResponse response = DnsResponse.builder()
                .header(header)
                .build();
        response.getQuestions().add(question);

        // Add multiple answers
        response.addAnswer(DnsRecord.createARecord("google.com", "8.8.8.8", 300));
        response.addAnswer(DnsRecord.createARecord("google.com", "8.8.4.4", 300));
        response.addAnswer(DnsRecord.createARecord("google.com", "1.1.1.1", 300));

        byte[] bytes = response.toBytes();

        assertNotNull(bytes);
        assertTrue(bytes.length > 12);

        // Verify answer count in header
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.position(6);  // Skip to ANCOUNT
        int ancount = buffer.getShort() & 0xFFFF;
        assertEquals(3, ancount);
    }

    @Test
    void testToBytes_EmptyResponse() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .qr(true)
                .rcode(3)  // NXDOMAIN
                .build();

        DnsResponse response = DnsResponse.builder()
                .header(header)
                .build();

        byte[] bytes = response.toBytes();

        assertNotNull(bytes);
        assertEquals(12, bytes.length);  // Just the header

        // Verify RCODE
        assertEquals(3, bytes[3] & 0x0F);
    }

    @Test
    void testAuthorityAndAdditionalSections() {
        DnsResponse response = DnsResponse.builder().build();

        DnsRecord nsRecord = DnsRecord.builder()
                .name("example.com")
                .type(2)  // NS
                .rclass(1)
                .ttl(3600)
                .rdata(new byte[]{5, 110, 115, 49, 3, 99, 111, 109, 0})  // ns1.com
                .build();

        DnsRecord glueRecord = DnsRecord.createARecord("ns1.com", "1.2.3.4", 3600);

        response.getAuthority().add(nsRecord);
        response.getAdditional().add(glueRecord);

        assertEquals(1, response.getAuthority().size());
        assertEquals(1, response.getAdditional().size());
    }

    @Test
    void testToString() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .build();

        DnsResponse response = DnsResponse.builder()
                .header(header)
                .build();

        String str = response.toString();
        assertNotNull(str);
        assertTrue(str.contains("DnsResponse"));
        assertTrue(str.contains("header"));
        assertTrue(str.contains("questions"));
        assertTrue(str.contains("answers"));
    }

    @Test
    void testHeaderImmutability() {
        DnsResponse response = DnsResponse.builder().build();

        DnsRecord answer1 = DnsRecord.createARecord("example.com", "1.2.3.4", 3600);
        response.addAnswer(answer1);

        // addAnswer should update the header with a new instance
        DnsHeader header1 = response.getHeader();
        assertEquals(1, header1.ancount());

        DnsRecord answer2 = DnsRecord.createARecord("example.com", "5.6.7.8", 3600);
        response.addAnswer(answer2);

        DnsHeader header2 = response.getHeader();
        assertEquals(2, header2.ancount());
    }
}
