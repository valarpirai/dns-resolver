package org.valarpirai.protocol;

/**
 * DNS Question section
 * Contains the domain name being queried and the query type/class
 */
public record DnsQuestion(
        String name,      // Domain name (e.g., "example.com")
        int type,         // Query type (e.g., 1=A, 28=AAAA)
        int qclass        // Query class (usually 1=IN for Internet)
) {
    // Compact constructor for validation
    public DnsQuestion {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Domain name cannot be null or empty");
        }
    }

    // Builder pattern for Records
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private int type;
        private int qclass;

        public Builder name(String name) { this.name = name; return this; }
        public Builder type(int type) { this.type = type; return this; }
        public Builder qclass(int qclass) { this.qclass = qclass; return this; }

        public DnsQuestion build() {
            return new DnsQuestion(name, type, qclass);
        }
    }

    public String getTypeName() {
        return DnsRecord.typeName(type);
    }

    public String getClassName() {
        return switch (qclass) {
            case 1 -> "IN";
            case 2 -> "CS";
            case 3 -> "CH";
            case 4 -> "HS";
            case 255 -> "ANY";
            default -> "CLASS_" + qclass;
        };
    }
}
