package org.valarpirai.server;

import org.valarpirai.protocol.DnsRecord;

/**
 * Formats raw bytes and record data for log lines.
 */
final class DnsLogFormat {
    private DnsLogFormat() {}

    /**
     * Utility method to convert bytes to hex string
     */
    static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X ", b));
        }
        return result.toString().trim();
    }

    /**
     * Format DNS record data for logging
     */
    static String recordData(DnsRecord record) {
        if (record.rdata() == null || record.rdata().length == 0) {
            return "empty";
        }

        return switch (record.type()) {
            case 1 -> {  // A record (IPv4)
                if (record.rdata().length == 4) {
                    yield String.format("%d.%d.%d.%d",
                            record.rdata()[0] & 0xFF,
                            record.rdata()[1] & 0xFF,
                            record.rdata()[2] & 0xFF,
                            record.rdata()[3] & 0xFF);
                }
                yield bytesToHex(record.rdata());
            }
            case 28 -> { // AAAA record (IPv6)
                if (record.rdata().length == 16) {
                    StringBuilder ipv6 = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) ipv6.append(":");
                        ipv6.append(String.format("%02x%02x",
                                record.rdata()[i] & 0xFF,
                                record.rdata()[i + 1] & 0xFF));
                    }
                    yield ipv6.toString();
                }
                yield bytesToHex(record.rdata());
            }
            case 5, 2, 12 -> { // CNAME, NS, PTR (domain names)
                try {
                    StringBuilder name = new StringBuilder();
                    int pos = 0;
                    while (pos < record.rdata().length) {
                        int len = record.rdata()[pos] & 0xFF;
                        if (len == 0) break;
                        if ((len & 0xC0) == 0xC0) break; // Pointer
                        pos++;
                        if (name.length() > 0) name.append(".");
                        for (int i = 0; i < len && pos < record.rdata().length; i++) {
                            name.append((char) record.rdata()[pos++]);
                        }
                    }
                    yield name.toString();
                } catch (Exception e) {
                    yield bytesToHex(record.rdata());
                }
            }
            case 16 -> { // TXT record
                try {
                    StringBuilder txt = new StringBuilder("\"");
                    int pos = 0;
                    while (pos < record.rdata().length) {
                        int len = record.rdata()[pos++] & 0xFF;
                        for (int i = 0; i < len && pos < record.rdata().length; i++) {
                            txt.append((char) record.rdata()[pos++]);
                        }
                    }
                    txt.append("\"");
                    yield txt.toString();
                } catch (Exception e) {
                    yield bytesToHex(record.rdata());
                }
            }
            default -> bytesToHex(record.rdata());
        };
    }
}
