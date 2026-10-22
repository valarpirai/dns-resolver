package org.valarpirai.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * DNS Request (Query) structure
 * Contains header and question sections
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsRequest {
    @Builder.Default
    private DnsHeader header = DnsHeader.builder().build();

    @Builder.Default
    private List<DnsQuestion> questions = new ArrayList<>();

    private byte[] rawData;  // Original raw query data

    public void addQuestion(DnsQuestion question) {
        this.questions.add(question);
    }

    /**
     * Parse DNS request from raw byte array
     */
    public static DnsRequest parse(byte[] data) {
        if (data == null || data.length < 12) {
            throw new IllegalArgumentException("Invalid DNS query: too short");
        }

        DnsRequest request = new DnsRequest();
        request.setRawData(data);

        // Parse header (12 bytes)
        int id = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);

        int flags1 = data[2] & 0xFF;
        int flags2 = data[3] & 0xFF;

        boolean qr = (flags1 & 0x80) != 0;
        int opcode = (flags1 >> 3) & 0x0F;
        boolean aa = (flags1 & 0x04) != 0;
        boolean tc = (flags1 & 0x02) != 0;
        boolean rd = (flags1 & 0x01) != 0;

        boolean ra = (flags2 & 0x80) != 0;
        int rcode = flags2 & 0x0F;

        int qdcount = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        int ancount = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
        int nscount = ((data[8] & 0xFF) << 8) | (data[9] & 0xFF);
        int arcount = ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);

        DnsHeader header = DnsHeader.builder()
                .id(id)
                .qr(qr)
                .opcode(opcode)
                .aa(aa)
                .tc(tc)
                .rd(rd)
                .ra(ra)
                .rcode(rcode)
                .qdcount(qdcount)
                .ancount(ancount)
                .nscount(nscount)
                .arcount(arcount)
                .build();

        request.setHeader(header);

        // Parse questions
        int position = 12;
        for (int i = 0; i < header.qdcount() && position < data.length; i++) {
            try {
                // Parse domain name
                StringBuilder domainName = new StringBuilder();
                while (position < data.length) {
                    int labelLength = data[position] & 0xFF;

                    if (labelLength == 0) {
                        position++;
                        break;
                    }

                    if ((labelLength & 0xC0) == 0xC0) {
                        // Compressed label (pointer) - skip for now
                        position += 2;
                        break;
                    }

                    position++;

                    if (position + labelLength > data.length) {
                        break;
                    }

                    if (domainName.length() > 0) {
                        domainName.append('.');
                    }

                    for (int j = 0; j < labelLength; j++) {
                        domainName.append((char) data[position + j]);
                    }

                    position += labelLength;
                }

                String name = domainName.toString();

                // Parse QTYPE (2 bytes)
                int qtype = 0;
                if (position + 1 < data.length) {
                    qtype = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                    position += 2;
                }

                // Parse QCLASS (2 bytes)
                int qclass = 0;
                if (position + 1 < data.length) {
                    qclass = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                    position += 2;
                }

                DnsQuestion question = DnsQuestion.builder()
                        .name(name)
                        .type(qtype)
                        .qclass(qclass)
                        .build();

                request.addQuestion(question);
            } catch (Exception e) {
                // Skip malformed question
                break;
            }
        }

        return request;
    }

    @Override
    public String toString() {
        return "DnsRequest{" +
                "header=" + header +
                ", questions=" + questions +
                '}';
    }
}
