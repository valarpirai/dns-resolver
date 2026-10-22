package org.valarpirai.protocol;

import lombok.Builder;

/**
 * DNS Header structure (12 bytes)
 * <pre>
 *  0  1  2  3  4  5  6  7  8  9  0  1  2  3  4  5
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                      ID                       |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |QR|   Opcode  |AA|TC|RD|RA|   Z    |   RCODE   |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    QDCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    ANCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    NSCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * |                    ARCOUNT                    |
 * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
 * </pre>
 */
@Builder
public record DnsHeader(
        int id,              // 16-bit identifier
        boolean qr,          // Query/Response flag (0=query, 1=response)
        int opcode,          // Operation code
        boolean aa,          // Authoritative Answer
        boolean tc,          // Truncation
        boolean rd,          // Recursion Desired
        boolean ra,          // Recursion Available
        int rcode,           // Response code
        int qdcount,         // Question count
        int ancount,         // Answer count
        int nscount,         // Name Server count
        int arcount          // Additional Records count
) {
    // Compact constructor for validation
    public DnsHeader {
        if (id < 0 || id > 65535) {
            throw new IllegalArgumentException("Invalid DNS ID: " + id);
        }
    }

    // Convenience methods for creating modified copies
    public DnsHeader withRcode(int newRcode) {
        return new DnsHeader(id, qr, opcode, aa, tc, rd, ra, newRcode,
                qdcount, ancount, nscount, arcount);
    }

    public DnsHeader withQdcount(int newQdcount) {
        return new DnsHeader(id, qr, opcode, aa, tc, rd, ra, rcode,
                newQdcount, ancount, nscount, arcount);
    }

    public DnsHeader withAncount(int newAncount) {
        return new DnsHeader(id, qr, opcode, aa, tc, rd, ra, rcode,
                qdcount, newAncount, nscount, arcount);
    }

    public DnsHeader withTc(boolean newTc) {
        return new DnsHeader(id, qr, opcode, aa, newTc, rd, ra, rcode,
                qdcount, ancount, nscount, arcount);
    }

    public DnsHeader withRa(boolean newRa) {
        return new DnsHeader(id, qr, opcode, aa, tc, rd, newRa, rcode,
                qdcount, ancount, nscount, arcount);
    }
}
