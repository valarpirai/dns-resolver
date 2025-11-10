package org.valarpirai;

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
    private DnsHeader header = new DnsHeader();

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
        DnsHeader header = new DnsHeader();
        header.setId(((data[0] & 0xFF) << 8) | (data[1] & 0xFF));

        int flags1 = data[2] & 0xFF;
        int flags2 = data[3] & 0xFF;

        header.setQr((flags1 & 0x80) != 0);
        header.setOpcode((flags1 >> 3) & 0x0F);
        header.setAa((flags1 & 0x04) != 0);
        header.setTc((flags1 & 0x02) != 0);
        header.setRd((flags1 & 0x01) != 0);

        header.setRa((flags2 & 0x80) != 0);
        header.setRcode(flags2 & 0x0F);

        header.setQdcount(((data[4] & 0xFF) << 8) | (data[5] & 0xFF));
        header.setAncount(((data[6] & 0xFF) << 8) | (data[7] & 0xFF));
        header.setNscount(((data[8] & 0xFF) << 8) | (data[9] & 0xFF));
        header.setArcount(((data[10] & 0xFF) << 8) | (data[11] & 0xFF));

        request.setHeader(header);

        // Parse questions
        int position = 12;
        for (int i = 0; i < header.getQdcount() && position < data.length; i++) {
            try {
                DnsQuestion question = new DnsQuestion();

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

                question.setName(domainName.toString());

                // Parse QTYPE (2 bytes)
                if (position + 1 < data.length) {
                    int qtype = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                    question.setType(qtype);
                    position += 2;
                }

                // Parse QCLASS (2 bytes)
                if (position + 1 < data.length) {
                    int qclass = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
                    question.setQclass(qclass);
                    position += 2;
                }

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
