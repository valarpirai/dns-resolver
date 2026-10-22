package org.valarpirai.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

    // Resolution statistics
    @Builder.Default
    private boolean cacheHit = false;
    @Builder.Default
    private int queriesMade = 0;
    @Builder.Default
    private int maxDepthReached = 0;

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
        return serialize(answers, header);
    }

    /**
     * Convert DNS response to bytes that fit in one UDP packet.
     * Trailing answers are dropped and the TC bit is set when the full response is too large.
     *
     * @param maxSize largest packet the client accepts
     * @return response bytes no longer than maxSize (unless the question alone exceeds it)
     */
    public byte[] toBytes(int maxSize) {
        byte[] full = serialize(answers, header);
        if (full.length <= maxSize) {
            return full;
        }

        List<DnsRecord> kept = new ArrayList<>(answers);
        byte[] truncated;
        do {
            if (!kept.isEmpty()) {
                kept.remove(kept.size() - 1);
            }
            truncated = serialize(kept, header.withTc(true));
        } while (truncated.length > maxSize && !kept.isEmpty());
        return truncated;
    }

    private byte[] serialize(List<DnsRecord> records, DnsHeader hdr) {
        // Exact upper bound: each name takes at most length + 2 bytes on the wire
        int size = 12;
        for (DnsQuestion question : questions) {
            size += question.name().length() + 2 + 4;
        }
        for (DnsRecord record : records) {
            size += record.name().length() + 2 + 10 + (record.rdata() != null ? record.rdata().length : 0);
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);

        // Header counts reflect the data actually written
        hdr = hdr.withQdcount(questions.size()).withAncount(records.size());

        // Write header
        buffer.putShort((short) hdr.id());

        // Flags byte 1
        int flags1 = 0;
        if (hdr.qr()) flags1 |= 0x80;
        flags1 |= (hdr.opcode() & 0x0F) << 3;
        if (hdr.aa()) flags1 |= 0x04;
        if (hdr.tc()) flags1 |= 0x02;
        if (hdr.rd()) flags1 |= 0x01;
        buffer.put((byte) flags1);

        // Flags byte 2
        int flags2 = 0;
        if (hdr.ra()) flags2 |= 0x80;
        flags2 |= (hdr.rcode() & 0x0F);
        buffer.put((byte) flags2);

        buffer.putShort((short) hdr.qdcount());
        buffer.putShort((short) hdr.ancount());
        buffer.putShort((short) hdr.nscount());
        buffer.putShort((short) hdr.arcount());

        // Write questions
        for (DnsQuestion question : questions) {
            writeQName(buffer, question.name());
            buffer.putShort((short) question.type());
            buffer.putShort((short) question.qclass());
        }

        // Write all answers
        for (DnsRecord answer : records) {
            writeQName(buffer, answer.name());
            buffer.putShort((short) answer.type());
            buffer.putShort((short) answer.rclass());
            buffer.putInt(answer.ttl());
            buffer.putShort((short) answer.rdlength());
            if (answer.rdata() != null) {
                buffer.put(answer.rdata());
            }
        }

        return Arrays.copyOf(buffer.array(), buffer.position());
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
            byte[] bytes = label.getBytes(StandardCharsets.ISO_8859_1);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new DnsException(DnsException.FORMERR, "Invalid DNS label in name: " + name);
            }
            buffer.put((byte) bytes.length);
            buffer.put(bytes);
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
