package org.valarpirai.protocol;

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

    // Builder pattern for Records
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int id;
        private boolean qr;
        private int opcode;
        private boolean aa;
        private boolean tc;
        private boolean rd;
        private boolean ra;
        private int rcode;
        private int qdcount;
        private int ancount;
        private int nscount;
        private int arcount;

        public Builder id(int id) { this.id = id; return this; }
        public Builder qr(boolean qr) { this.qr = qr; return this; }
        public Builder opcode(int opcode) { this.opcode = opcode; return this; }
        public Builder aa(boolean aa) { this.aa = aa; return this; }
        public Builder tc(boolean tc) { this.tc = tc; return this; }
        public Builder rd(boolean rd) { this.rd = rd; return this; }
        public Builder ra(boolean ra) { this.ra = ra; return this; }
        public Builder rcode(int rcode) { this.rcode = rcode; return this; }
        public Builder qdcount(int qdcount) { this.qdcount = qdcount; return this; }
        public Builder ancount(int ancount) { this.ancount = ancount; return this; }
        public Builder nscount(int nscount) { this.nscount = nscount; return this; }
        public Builder arcount(int arcount) { this.arcount = arcount; return this; }

        public DnsHeader build() {
            return new DnsHeader(id, qr, opcode, aa, tc, rd, ra, rcode,
                    qdcount, ancount, nscount, arcount);
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

    public DnsHeader withRa(boolean newRa) {
        return new DnsHeader(id, qr, opcode, aa, tc, rd, newRa, rcode,
                qdcount, ancount, nscount, arcount);
    }
}
