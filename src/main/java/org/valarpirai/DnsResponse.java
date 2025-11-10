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
    private DnsHeader header = new DnsHeader();

    @Builder.Default
    private List<DnsQuestion> questions = new ArrayList<>();

    @Builder.Default
    private List<DnsRecord> answers = new ArrayList<>();

    public DnsResponse(DnsRequest request) {
        this.header = new DnsHeader();
        this.questions = new ArrayList<>();
        this.answers = new ArrayList<>();

        // Copy header from request
        this.header.setId(request.getHeader().getId());
        this.header.setQr(true); // This is a response
        this.header.setOpcode(request.getHeader().getOpcode());
        this.header.setRd(request.getHeader().isRd());
        this.header.setQdcount(request.getHeader().getQdcount());

        // Copy questions from request
        this.questions.addAll(request.getQuestions());
    }

    public void addAnswer(DnsRecord answer) {
        this.answers.add(answer);
        this.header.setAncount(this.answers.size());
    }

    /**
     * Convert DNS response to byte array for transmission
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Write header
        buffer.putShort((short) header.getId());

        // Flags byte 1
        int flags1 = 0;
        if (header.isQr()) flags1 |= 0x80;
        flags1 |= (header.getOpcode() & 0x0F) << 3;
        if (header.isAa()) flags1 |= 0x04;
        if (header.isTc()) flags1 |= 0x02;
        if (header.isRd()) flags1 |= 0x01;
        buffer.put((byte) flags1);

        // Flags byte 2
        int flags2 = 0;
        if (header.isRa()) flags2 |= 0x80;
        flags2 |= (header.getRcode() & 0x0F);
        buffer.put((byte) flags2);

        buffer.putShort((short) header.getQdcount());
        buffer.putShort((short) header.getAncount());
        buffer.putShort((short) header.getNscount());
        buffer.putShort((short) header.getArcount());

        // Write questions
        for (DnsQuestion question : questions) {
            writeQName(buffer, question.getName());
            buffer.putShort((short) question.getType());
            buffer.putShort((short) question.getQclass());
        }

        // Write answers
        for (DnsRecord answer : answers) {
            writeQName(buffer, answer.getName());
            buffer.putShort((short) answer.getType());
            buffer.putShort((short) answer.getRclass());
            buffer.putInt(answer.getTtl());
            buffer.putShort((short) answer.getRdlength());
            buffer.put(answer.getRdata());
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
