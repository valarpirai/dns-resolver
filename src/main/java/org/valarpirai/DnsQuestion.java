package org.valarpirai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DNS Question section
 * Contains the domain name being queried and the query type/class
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsQuestion {
    private String name;      // Domain name (e.g., "example.com")
    private int type;         // Query type (e.g., 1=A, 28=AAAA)
    private int qclass;       // Query class (usually 1=IN for Internet)

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
            case 255 -> "ANY";
            default -> "TYPE_" + type;
        };
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
