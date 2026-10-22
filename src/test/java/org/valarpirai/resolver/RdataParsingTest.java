package org.valarpirai.resolver;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.valarpirai.protocol.DnsException;
import org.valarpirai.util.Configuration;

class RdataParsingTest {
    private final RecursiveDnsResolver resolver = new RecursiveDnsResolver(Configuration.getInstance());

    @Test
    void expandsCompressedCnameTarget() {
        // Packet: offset 12 holds "example.com"; the CNAME data is "www" + pointer to offset 12
        byte[] name = {7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0};
        byte[] rdata = {3, 'w', 'w', 'w', (byte) 0xC0, 12};
        byte[] packet = new byte[12 + name.length + rdata.length];
        System.arraycopy(name, 0, packet, 12, name.length);
        System.arraycopy(rdata, 0, packet, 12 + name.length, rdata.length);

        byte[] expanded = DnsWire.expandRdata(packet, 12 + name.length, rdata.length, 5);

        // Standalone, so the name parses without the original packet
        assertEquals("www.example.com", DnsWire.parseDomainName(expanded, 0).name);
        assertTrue(new String(expanded, StandardCharsets.ISO_8859_1).indexOf(0xC0) < 0);
    }

    @Test
    void keepsMxPreferenceBeforeName() {
        byte[] rdata = {0, 10, 4, 'm', 'a', 'i', 'l', 0};
        byte[] expanded = DnsWire.expandRdata(rdata, 0, rdata.length, 15);
        assertArrayEquals(rdata, expanded);
    }

    @Test
    void pointerLoopThrowsInsteadOfHanging() {
        byte[] loop = new byte[14];
        loop[12] = (byte) 0xC0;
        loop[13] = 12; // points at itself
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                () -> assertThrows(DnsException.class, () -> DnsWire.parseDomainName(loop, 12)));
    }
}
