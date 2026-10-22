package org.valarpirai.resolver;

import lombok.extern.slf4j.Slf4j;
import org.valarpirai.protocol.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds upstream queries and parses upstream replies, including name decompression.
 */
@Slf4j
final class DnsWire {
    private static final int MAX_POINTER_JUMPS = 32;

    private DnsWire() {}

    /**
     * Build DNS query bytes
     */
    static byte[] buildQuery(DnsRequest request) {
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Header
        buffer.putShort((short) request.getHeader().id());
        buffer.put((byte) (request.getHeader().rd() ? 0x01 : 0x00)); // Recursion desired
        buffer.put((byte) 0x00);
        buffer.putShort((short) 1); // 1 question
        buffer.putShort((short) 0); // 0 answers
        buffer.putShort((short) 0); // 0 authority
        buffer.putShort((short) 0); // 0 additional

        // Question
        DnsQuestion question = request.getQuestions().get(0);
        String[] labels = question.name().split("\\.");
        for (String label : labels) {
            byte[] bytes = label.getBytes(StandardCharsets.ISO_8859_1);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new DnsException(DnsException.FORMERR, "Invalid DNS label in name: " + question.name());
            }
            buffer.put((byte) bytes.length);
            buffer.put(bytes);
        }
        buffer.put((byte) 0); // End of name

        buffer.putShort((short) question.type());
        buffer.putShort((short) question.qclass());

        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    /**
     * Parse full DNS response including authority and additional sections
     */
    static DnsResponse parseFullResponse(byte[] data, DnsRequest originalRequest) {
        try {
            if (data.length < 12) {
                return null;
            }

            // Parse header
            DnsHeader header = DnsHeader.builder()
                    .id(((data[0] & 0xFF) << 8) | (data[1] & 0xFF))
                    .qr((data[2] & 0x80) != 0)
                    .tc((data[2] & 0x02) != 0)
                    .rcode(data[3] & 0x0F)
                    .qdcount(((data[4] & 0xFF) << 8) | (data[5] & 0xFF))
                    .ancount(((data[6] & 0xFF) << 8) | (data[7] & 0xFF))
                    .nscount(((data[8] & 0xFF) << 8) | (data[9] & 0xFF))
                    .arcount(((data[10] & 0xFF) << 8) | (data[11] & 0xFF))
                    .build();

            DnsResponse response = DnsResponse.builder()
                    .header(header)
                    .questions(new ArrayList<>(originalRequest.getQuestions()))
                    .answers(new ArrayList<>())
                    .build();

            // Skip questions
            int position = 12;
            for (int i = 0; i < header.qdcount() && position < data.length; i++) {
                position = skipDomainName(data, position);
                position += 4; // Skip QTYPE and QCLASS
            }

            // Parse answers
            for (int i = 0; i < header.ancount() && position < data.length; i++) {
                ParseResult result = parseResourceRecord(data, position);
                if (result != null) {
                    response.addAnswer(result.record);
                    position = result.newPosition;
                }
            }

            // Parse authority section (for referrals)
            List<DnsRecord> authorityRecords = new ArrayList<>();
            for (int i = 0; i < header.nscount() && position < data.length; i++) {
                ParseResult result = parseResourceRecord(data, position);
                if (result != null) {
                    authorityRecords.add(result.record);
                    position = result.newPosition;
                }
            }
            response.setAuthority(authorityRecords);

            // Parse additional section (glue records)
            List<DnsRecord> additionalRecords = new ArrayList<>();
            for (int i = 0; i < header.arcount() && position < data.length; i++) {
                ParseResult result = parseResourceRecord(data, position);
                if (result != null) {
                    additionalRecords.add(result.record);
                    position = result.newPosition;
                }
            }
            response.setAdditional(additionalRecords);

            return response;

        } catch (Exception e) {
            log.warn("Failed to parse DNS response", e);
            return null;
        }
    }

    private static int skipDomainName(byte[] data, int position) {
        while (position < data.length) {
            int labelLength = data[position] & 0xFF;
            if (labelLength == 0) return position + 1;
            if ((labelLength & 0xC0) == 0xC0) return position + 2;
            position += labelLength + 1;
        }
        return position;
    }

    private static ParseResult parseResourceRecord(byte[] data, int position) {
        try {
            NameParseResult nameResult = parseDomainName(data, position);
            position = nameResult.newPosition;

            if (position + 10 > data.length) return null;

            int type = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            int rclass = ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            int ttl = ((data[position + 4] & 0xFF) << 24) | ((data[position + 5] & 0xFF) << 16) |
                      ((data[position + 6] & 0xFF) << 8) | (data[position + 7] & 0xFF);
            int rdlength = ((data[position + 8] & 0xFF) << 8) | (data[position + 9] & 0xFF);
            position += 10;

            if (position + rdlength > data.length) return null;

            byte[] rdata = expandRdata(data, position, rdlength, type);
            position += rdlength;

            DnsRecord record = DnsRecord.builder()
                    .name(nameResult.name)
                    .type(type)
                    .rclass(rclass)
                    .ttl(ttl)
                    .rdata(rdata)
                    .build();

            return new ParseResult(record, position);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Copy record data, expanding compressed names so the bytes stay valid outside the source packet
     *
     * @param data   full packet
     * @param start  offset of the record data
     * @param length record data length
     * @param type   record type
     * @return self-contained record data
     */
    static byte[] expandRdata(byte[] data, int start, int length, int type) {
        // Number of fixed bytes before the first name, and how many names follow
        int prefix;
        int names;
        switch (type) {
            case 2, 5, 12 -> { prefix = 0; names = 1; }   // NS, CNAME, PTR
            case 15 -> { prefix = 2; names = 1; }         // MX
            case 33 -> { prefix = 6; names = 1; }         // SRV
            case 6 -> { prefix = 0; names = 2; }          // SOA (followed by 20 fixed bytes)
            default -> { return Arrays.copyOfRange(data, start, start + length); }
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int pos = start;
        out.write(data, pos, prefix);
        pos += prefix;
        for (int i = 0; i < names; i++) {
            NameParseResult name = parseDomainName(data, pos);
            writeName(out, name.name);
            pos = name.newPosition;
        }
        int end = start + length;
        if (pos < end) {
            out.write(data, pos, end - pos);
        }
        return out.toByteArray();
    }

    private static void writeName(ByteArrayOutputStream out, String name) {
        if (!name.isEmpty()) {
            for (String label : name.split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.ISO_8859_1);
                out.write(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
        }
        out.write(0);
    }

    static NameParseResult parseDomainName(byte[] data, int position) {
        StringBuilder name = new StringBuilder();
        int originalPosition = position;
        boolean jumped = false;
        int jumps = 0;

        while (position < data.length) {
            int labelLength = data[position] & 0xFF;

            if (labelLength == 0) {
                if (!jumped) position++;
                break;
            }

            if ((labelLength & 0xC0) == 0xC0) {
                // Bound the hops so a pointer loop cannot hang the resolver
                if (position + 1 >= data.length || ++jumps > MAX_POINTER_JUMPS) {
                    throw new DnsException(DnsException.SERVFAIL, "Bad compression pointer");
                }
                if (!jumped) {
                    originalPosition = position + 2;
                    jumped = true;
                }
                int pointer = ((labelLength & 0x3F) << 8) | (data[position + 1] & 0xFF);
                position = pointer;
                continue;
            }

            position++;
            if (position + labelLength > data.length) break;

            if (name.length() > 0) name.append('.');
            for (int i = 0; i < labelLength; i++) {
                name.append((char) data[position + i]);
            }
            position += labelLength;
        }

        return new NameParseResult(name.toString(), jumped ? originalPosition : position);
    }

    static String parseDomainFromRdata(byte[] rdata) {
        if (rdata == null || rdata.length == 0) return null;
        NameParseResult result = parseDomainName(rdata, 0);
        return result.name;
    }

    private static class ParseResult {
        final DnsRecord record;
        final int newPosition;

        ParseResult(DnsRecord record, int newPosition) {
            this.record = record;
            this.newPosition = newPosition;
        }
    }

    static class NameParseResult {
        final String name;
        final int newPosition;

        NameParseResult(String name, int newPosition) {
            this.name = name;
            this.newPosition = newPosition;
        }
    }
}
