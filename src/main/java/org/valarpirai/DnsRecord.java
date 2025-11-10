package org.valarpirai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DNS Resource Record (Answer/Authority/Additional)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsRecord {
    private String name;       // Domain name
    private int type;          // Record type (A, AAAA, CNAME, etc.)
    private int rclass;        // Record class (usually 1=IN)
    private int ttl;           // Time to live in seconds
    private int rdlength;      // Length of rdata
    private byte[] rdata;      // Record data

    public void setRdata(byte[] rdata) {
        this.rdata = rdata;
        this.rdlength = rdata != null ? rdata.length : 0;
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
                .rdlength(rdata.length)
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
                .rdlength(ipv6Address.length)
                .build();
    }
}
