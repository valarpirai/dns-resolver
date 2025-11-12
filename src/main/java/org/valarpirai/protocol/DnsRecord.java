package org.valarpirai.protocol;

/**
 * DNS Resource Record (Answer/Authority/Additional)
 */
public record DnsRecord(
        String name,       // Domain name
        int type,          // Record type (A, AAAA, CNAME, etc.)
        int rclass,        // Record class (usually 1=IN)
        int ttl,           // Time to live in seconds
        int rdlength,      // Length of rdata
        byte[] rdata       // Record data
) {
    // Compact constructor for validation and auto-calculation
    public DnsRecord {
        if (name == null) {
            throw new IllegalArgumentException("Record name cannot be null");
        }
        // Auto-calculate rdlength from rdata
        if (rdata != null) {
            rdlength = rdata.length;
        }
    }

    // Builder pattern for Records
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private int type;
        private int rclass;
        private int ttl;
        private int rdlength;
        private byte[] rdata;

        public Builder name(String name) { this.name = name; return this; }
        public Builder type(int type) { this.type = type; return this; }
        public Builder rclass(int rclass) { this.rclass = rclass; return this; }
        public Builder ttl(int ttl) { this.ttl = ttl; return this; }
        public Builder rdlength(int rdlength) { this.rdlength = rdlength; return this; }
        public Builder rdata(byte[] rdata) {
            this.rdata = rdata;
            if (rdata != null) {
                this.rdlength = rdata.length;
            }
            return this;
        }

        public DnsRecord build() {
            return new DnsRecord(name, type, rclass, ttl, rdlength, rdata);
        }
    }

    public String getTypeName() {
        return switch (type) {
            case 1 -> "A";
            case 2 -> "NS";
            case 5 -> "CNAME";
            case 6 -> "SOA";
            case 12 -> "PTR";
            case 15 -> "MX";
            case 16 -> "TXT";
            case 28 -> "AAAA";
            case 33 -> "SRV";
            default -> "TYPE_" + type;
        };
    }

    /**
     * Create an A record (IPv4 address)
     */
    public static DnsRecord createARecord(String name, String ipAddress, int ttl) {
        String[] parts = ipAddress.split("\\.");
        if (parts.length != 4) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + ipAddress);
        }

        byte[] rdata = new byte[4];
        for (int i = 0; i < 4; i++) {
            rdata[i] = (byte) Integer.parseInt(parts[i]);
        }

        return DnsRecord.builder()
                .name(name)
                .type(1)
                .rclass(1)
                .ttl(ttl)
                .rdata(rdata)
                .build();
    }

    /**
     * Create an AAAA record (IPv6 address)
     */
    public static DnsRecord createAAAARecord(String name, byte[] ipv6Address, int ttl) {
        if (ipv6Address.length != 16) {
            throw new IllegalArgumentException("Invalid IPv6 address length");
        }
        return DnsRecord.builder()
                .name(name)
                .type(28)
                .rclass(1)
                .ttl(ttl)
                .rdata(ipv6Address)
                .build();
    }
}
