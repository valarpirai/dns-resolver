package org.valarpirai;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * DNS Response structure
 * Contains header, questions, and answer sections
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DnsResponse {
    @Builder.Default
    private DnsHeader header = DnsHeader.builder().build();

    @Builder.Default
    private List<DnsQuestion> questions = new ArrayList<>();

    @Builder.Default
    private List<DnsRecord> answers = new ArrayList<>();

    @Builder.Default
    private List<DnsRecord> authority = new ArrayList<>();

    @Builder.Default
    private List<DnsRecord> additional = new ArrayList<>();

    public DnsResponse(DnsRequest request) {
        this.questions = new ArrayList<>();
        this.answers = new ArrayList<>();
        this.authority = new ArrayList<>();
        this.additional = new ArrayList<>();

        // Copy header from request
        DnsHeader reqHeader = request.getHeader();
        this.header = DnsHeader.builder()
                .id(reqHeader.id())
                .qr(true) // This is a response
                .opcode(reqHeader.opcode())
                .aa(false)
                .tc(false)
                .rd(reqHeader.rd())
                .ra(false)
                .rcode(0)
                .qdcount(reqHeader.qdcount())
                .ancount(0)
                .nscount(0)
                .arcount(0)
                .build();

        // Copy questions from request
        this.questions.addAll(request.getQuestions());
    }

    public void addAnswer(DnsRecord answer) {
        this.answers.add(answer);
        this.header = this.header.withAncount(this.answers.size());
    }

    /**
     * Convert DNS response to byte array for transmission
     */
    public byte[] toBytes() {
        // Calculate required buffer size dynamically to handle multiple answers
        int estimatedSize = 512 + (answers.size() * 256); // Account for multiple answers
        ByteBuffer buffer = ByteBuffer.allocate(estimatedSize);

        // Update header counts to reflect actual data
        header = header.withQdcount(questions.size());
        header = header.withAncount(answers.size());

        // Write header
        buffer.putShort((short) header.id());

        // Flags byte 1
        int flags1 = 0;
        if (header.qr()) flags1 |= 0x80;
        flags1 |= (header.opcode() & 0x0F) << 3;
        if (header.aa()) flags1 |= 0x04;
        if (header.tc()) flags1 |= 0x02;
        if (header.rd()) flags1 |= 0x01;
        buffer.put((byte) flags1);

        // Flags byte 2
        int flags2 = 0;
        if (header.ra()) flags2 |= 0x80;
        flags2 |= (header.rcode() & 0x0F);
        buffer.put((byte) flags2);

        buffer.putShort((short) header.qdcount());
        buffer.putShort((short) header.ancount());
        buffer.putShort((short) header.nscount());
        buffer.putShort((short) header.arcount());

        // Write questions
        for (DnsQuestion question : questions) {
            writeQName(buffer, question.name());
            buffer.putShort((short) question.type());
            buffer.putShort((short) question.qclass());
        }

        // Write all answers
        for (DnsRecord answer : answers) {
            writeQName(buffer, answer.name());
            buffer.putShort((short) answer.type());
            buffer.putShort((short) answer.rclass());
            buffer.putInt(answer.ttl());
            buffer.putShort((short) answer.rdlength());
            if (answer.rdata() != null) {
                buffer.put(answer.rdata());
            }
        }

        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    /**
     * Write domain name in DNS format (length-prefixed labels)
     */
    private void writeQName(ByteBuffer buffer, String name) {
        if (name == null || name.isEmpty()) {
            buffer.put((byte) 0);
            return;
        }

        String[] labels = name.split("\\.");
        for (String label : labels) {
            buffer.put((byte) label.length());
            buffer.put(label.getBytes());
        }
        buffer.put((byte) 0); // End of name
    }

    @Override
    public String toString() {
        return "DnsResponse{" +
                "header=" + header +
                ", questions=" + questions +
                ", answers=" + answers +
                '}';
    }
}
