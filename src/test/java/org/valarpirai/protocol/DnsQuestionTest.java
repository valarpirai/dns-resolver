package org.valarpirai.protocol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DnsQuestionTest {

    @Test
    void testBuilder() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)  // A record
                .qclass(1)  // IN class
                .build();

        assertEquals("example.com", question.name());
        assertEquals(1, question.type());
        assertEquals(1, question.qclass());
    }

    @Test
    void testValidation_ValidName() {
        assertDoesNotThrow(() -> DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build());
    }

    @Test
    void testValidation_NullName() {
        assertThrows(IllegalArgumentException.class, () ->
                DnsQuestion.builder()
                        .name(null)
                        .type(1)
                        .qclass(1)
                        .build());
    }

    @Test
    void testValidation_EmptyName() {
        assertThrows(IllegalArgumentException.class, () ->
                DnsQuestion.builder()
                        .name("")
                        .type(1)
                        .qclass(1)
                        .build());
    }

    @Test
    void testGetTypeName_ARecord() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        assertEquals("A", question.getTypeName());
    }

    @Test
    void testGetTypeName_NSRecord() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(2)
                .qclass(1)
                .build();

        assertEquals("NS", question.getTypeName());
    }

    @Test
    void testGetTypeName_AAAARecord() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(28)
                .qclass(1)
                .build();

        assertEquals("AAAA", question.getTypeName());
    }

    @Test
    void testGetTypeName_UnknownType() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(999)
                .qclass(1)
                .build();

        assertEquals("TYPE_999", question.getTypeName());
    }

    @Test
    void testGetClassName_IN() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        assertEquals("IN", question.getClassName());
    }

    @Test
    void testGetClassName_CS() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(2)
                .build();

        assertEquals("CS", question.getClassName());
    }

    @Test
    void testGetClassName_Unknown() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(999)
                .build();

        assertEquals("CLASS_999", question.getClassName());
    }

    @Test
    void testRecordImmutability() {
        DnsQuestion question = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        // Records are immutable - verify we can't modify the name
        String name = question.name();
        assertEquals("example.com", name);
    }

    @Test
    void testEqualsAndHashCode() {
        DnsQuestion q1 = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        DnsQuestion q2 = DnsQuestion.builder()
                .name("example.com")
                .type(1)
                .qclass(1)
                .build();

        DnsQuestion q3 = DnsQuestion.builder()
                .name("google.com")
                .type(1)
                .qclass(1)
                .build();

        assertEquals(q1, q2);
        assertNotEquals(q1, q3);
        assertEquals(q1.hashCode(), q2.hashCode());
    }
}
