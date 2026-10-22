package org.valarpirai.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DnsHeaderTest {

    @Test
    void testBuilder() {
        DnsHeader header = DnsHeader.builder()
                .id(12345)
                .qr(true)
                .opcode(0)
                .aa(false)
                .tc(false)
                .rd(true)
                .ra(true)
                .rcode(0)
                .qdcount(1)
                .ancount(2)
                .nscount(0)
                .arcount(0)
                .build();

        assertEquals(12345, header.id());
        assertTrue(header.qr());
        assertEquals(0, header.opcode());
        assertFalse(header.aa());
        assertFalse(header.tc());
        assertTrue(header.rd());
        assertTrue(header.ra());
        assertEquals(0, header.rcode());
        assertEquals(1, header.qdcount());
        assertEquals(2, header.ancount());
        assertEquals(0, header.nscount());
        assertEquals(0, header.arcount());
    }

    @Test
    void testValidation_ValidId() {
        assertDoesNotThrow(() -> DnsHeader.builder()
                .id(0)
                .build());

        assertDoesNotThrow(() -> DnsHeader.builder()
                .id(65535)
                .build());
    }

    @Test
    void testValidation_InvalidId() {
        assertThrows(IllegalArgumentException.class, () ->
                DnsHeader.builder()
                        .id(-1)
                        .build());

        assertThrows(IllegalArgumentException.class, () ->
                DnsHeader.builder()
                        .id(65536)
                        .build());
    }

    @Test
    void testWithRcode() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .rcode(0)
                .build();

        DnsHeader modified = header.withRcode(3);

        assertEquals(100, modified.id());
        assertEquals(3, modified.rcode());
        assertEquals(0, header.rcode()); // Original unchanged
    }

    @Test
    void testWithQdcount() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .qdcount(1)
                .build();

        DnsHeader modified = header.withQdcount(5);

        assertEquals(5, modified.qdcount());
        assertEquals(1, header.qdcount()); // Original unchanged
    }

    @Test
    void testWithAncount() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .ancount(0)
                .build();

        DnsHeader modified = header.withAncount(10);

        assertEquals(10, modified.ancount());
        assertEquals(0, header.ancount()); // Original unchanged
    }

    @Test
    void testWithRa() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .ra(false)
                .build();

        DnsHeader modified = header.withRa(true);

        assertTrue(modified.ra());
        assertFalse(header.ra()); // Original unchanged
    }

    @Test
    void testRecordImmutability() {
        DnsHeader header = DnsHeader.builder()
                .id(100)
                .qr(true)
                .build();

        // Records are immutable - with* methods return new instances
        DnsHeader modified = header.withRcode(5);

        assertNotSame(header, modified);
        assertEquals(0, header.rcode());
        assertEquals(5, modified.rcode());
    }

    @Test
    void testEqualsAndHashCode() {
        DnsHeader header1 = DnsHeader.builder()
                .id(100)
                .qr(true)
                .rcode(0)
                .build();

        DnsHeader header2 = DnsHeader.builder()
                .id(100)
                .qr(true)
                .rcode(0)
                .build();

        DnsHeader header3 = DnsHeader.builder()
                .id(200)
                .qr(true)
                .rcode(0)
                .build();

        assertEquals(header1, header2);
        assertNotEquals(header1, header3);
        assertEquals(header1.hashCode(), header2.hashCode());
    }
}
