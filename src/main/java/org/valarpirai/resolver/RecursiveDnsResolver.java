package org.valarpirai.resolver;

import lombok.extern.slf4j.Slf4j;
import org.valarpirai.protocol.*;
import org.valarpirai.util.Configuration;
import org.valarpirai.cache.DnsCache;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * True Recursive DNS Resolver
 * Starts from root servers and follows the DNS hierarchy
 */
@Slf4j
public class RecursiveDnsResolver {
    private static final int BUFFER_SIZE = 4096;
    private static final int MAX_POINTER_JUMPS = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final List<String> rootServers;
    private final DnsCache cache;
    private final Configuration config;
    private final int maxDepth;
    private final int timeoutMs;

    public RecursiveDnsResolver(Configuration config) {
        this.config = config;
        this.cache = new DnsCache(config);
        this.rootServers = new ArrayList<>();
        this.maxDepth = config.getInt("resolver.max.depth", 16);
        this.timeoutMs = config.getInt("resolver.timeout", 5000);

        // Load root servers
        String rootConfig = config.getString("resolver.root.servers",
                "198.41.0.4,199.9.14.201,192.33.4.12");
        for (String server : rootConfig.split(",")) {
            rootServers.add(server.trim());
        }

        log.info(String.format("Recursive DNS Resolver initialized with %d root servers, timeout: %d ms",
                rootServers.size(), timeoutMs));
    }

    /**
     * Resolve a DNS request recursively starting from root servers
     */
    public DnsResponse resolve(DnsRequest request) {
        long startTime = System.currentTimeMillis();

        if (request.getQuestions().isEmpty()) {
            return createErrorResponse(request, 1); // Format error
        }

        DnsQuestion question = request.getQuestions().get(0);
        String qname = question.name();
        int qtype = question.type();

        if (config.isDebugEnabled()) {
            log.info(String.format("=== Starting recursive resolution for %s (Type: %s) ===",
                    qname, question.getTypeName()));
        }

        // Check cache first
        long cacheStartTime = System.currentTimeMillis();
        List<DnsRecord> cachedRecords = cache.get(qname, qtype);
        if (cachedRecords != null) {
            long cacheTime = System.currentTimeMillis() - cacheStartTime;
            long totalTime = System.currentTimeMillis() - startTime;

            if (config.isDebugEnabled()) {
                log.info(String.format("Resolution completed in %d ms (cache: %d ms)",
                        totalTime, cacheTime));
            }

            DnsResponse response = new DnsResponse(request);
            response.setHeader(response.getHeader().withRcode(0).withRa(true));
            response.setCacheHit(true);
            response.setQueriesMade(0);
            response.setMaxDepthReached(0);
            for (DnsRecord record : cachedRecords) {
                response.addAnswer(record);
            }
            return response;
        }
        long cacheTime = System.currentTimeMillis() - cacheStartTime;

        // Start recursive resolution from root
        try {
            long resolveStartTime = System.currentTimeMillis();
            ResolutionStats stats = new ResolutionStats();
            List<DnsRecord> answers = resolveRecursive(qname, qtype, rootServers, 0, stats);
            long resolveTime = System.currentTimeMillis() - resolveStartTime;
            long totalTime = System.currentTimeMillis() - startTime;

            if (config.isDebugEnabled()) {
                log.info(String.format("Resolution completed in %d ms (cache lookup: %d ms, recursive resolve: %d ms, queries made: %d, max depth: %d)",
                        totalTime, cacheTime, resolveTime, stats.queriesMade, stats.maxDepthReached));
            }

            DnsResponse response = new DnsResponse(request);
            response.setHeader(response.getHeader()
                    .withRcode(answers.isEmpty() ? emptyAnswerRcode(stats) : 0)
                    .withRa(true));
            response.setCacheHit(false);
            response.setQueriesMade(stats.queriesMade);
            response.setMaxDepthReached(stats.maxDepthReached);

            for (DnsRecord answer : answers) {
                response.addAnswer(answer);
            }

            // Cache the result
            if (!answers.isEmpty()) {
                cache.put(qname, qtype, answers);
            }

            return response;

        } catch (DnsException e) {
            log.warn("Resolution failed for " + qname + ": " + e.getMessage());
            return createErrorResponse(request, e.getRcode());
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("Recursive resolution failed for " + qname + " after " + totalTime + " ms", e);
            return createErrorResponse(request, 2); // Server failure
        }
    }

    /**
     * Recursive resolution following DNS hierarchy
     */
    private List<DnsRecord> resolveRecursive(String qname, int qtype, List<String> nameservers, int depth, ResolutionStats stats) {
        stats.maxDepthReached = Math.max(stats.maxDepthReached, depth);

        if (depth > maxDepth) {
            log.warn("Max recursion depth reached for " + qname);
            return new ArrayList<>();
        }

        if (config.isDebugEnabled()) {
            log.info(String.format("[Depth %d] Querying for %s (Type: %s) using %d nameserver(s)",
                    depth, qname, getTypeName(qtype), nameservers.size()));
        }

        // Try each nameserver
        for (String ns : nameservers) {
            try {
                stats.queriesMade++;
                DnsResponse response = queryNameserver(qname, qtype, ns);
                if (response == null) continue;

                int rcode = response.getHeader().rcode();
                if (rcode != 0 && rcode != 3) continue; // SERVFAIL, REFUSED etc: try the next server

                if (!response.getAnswers().isEmpty()) {
                    if (config.isDebugEnabled()) {
                        log.info(String.format("[Depth %d] Got %d answer(s) from %s",
                                depth, response.getAnswers().size(), ns));
                    }

                    List<DnsRecord> answers = response.getAnswers();
                    String cnameTarget = qtype == 5 ? null : unresolvedCnameTarget(qname, qtype, answers);
                    if (cnameTarget == null) {
                        return answers;
                    }

                    if (config.isDebugEnabled()) {
                        log.info(String.format("[Depth %d] Following CNAME: %s -> %s", depth, qname, cnameTarget));
                    }
                    List<DnsRecord> allAnswers = new ArrayList<>(answers);
                    allAnswers.addAll(resolveRecursive(cnameTarget, qtype, rootServers, depth + 1, stats));
                    return allAnswers;
                }

                // Check for referral (authority/additional sections with NS records)
                List<String> referralNS = extractReferralNameservers(response, depth, stats);
                if (!referralNS.isEmpty()) {
                    if (config.isDebugEnabled()) {
                        log.info(String.format("[Depth %d] Got referral to %d nameserver(s)",
                                depth, referralNS.size()));
                    }
                    // The child zone's servers were already all tried; siblings would return the same referral
                    return resolveRecursive(qname, qtype, referralNS, depth + 1, stats);
                }

                // Authoritative empty answer: NXDOMAIN (rcode 3) or NODATA (rcode 0)
                stats.definitive = true;
                stats.nxdomain = rcode == 3;
                return new ArrayList<>();

            } catch (SocketTimeoutException e) {
                log.warn(String.format("Nameserver %s timed out after %d ms", ns, timeoutMs));
            } catch (Exception e) {
                log.warn("Failed to query nameserver " + ns, e);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Follow the CNAME chain in an answer set
     *
     * @param qname   the name that was asked for
     * @param qtype   the type that was asked for
     * @param answers answer records from one upstream response
     * @return the name still to be resolved, or null if the answers already hold the wanted type
     */
    private String unresolvedCnameTarget(String qname, int qtype, List<DnsRecord> answers) {
        String current = qname;
        String target = null;
        for (int hops = 0; hops <= maxDepth; hops++) {
            final String name = current;
            DnsRecord cname = answers.stream()
                    .filter(r -> r.type() == 5 && r.name().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (cname == null) break;
            String next = parseDomainFromRdata(cname.rdata());
            if (next == null || next.isEmpty()) break;
            target = current = next;
        }
        if (target == null) return null;

        // The upstream server may already have included the final records
        final String last = target;
        boolean resolved = answers.stream().anyMatch(r -> r.type() == qtype && r.name().equalsIgnoreCase(last));
        return resolved ? null : target;
    }

    /**
     * Query a specific nameserver
     */
    private DnsResponse queryNameserver(String qname, int qtype, String nameserver) throws IOException {
        int id = RANDOM.nextInt(65536);
        DnsRequest request = DnsRequest.builder()
                .header(DnsHeader.builder().id(id).qr(false).rd(false).qdcount(1).build())
                .build();
        request.addQuestion(DnsQuestion.builder().name(qname).type(qtype).qclass(1).build());
        byte[] queryBytes = buildQuery(request);

        InetSocketAddress serverSocket = new InetSocketAddress(InetAddress.getByName(nameserver), 53);
        long queryStartTime = System.currentTimeMillis();

        // DatagramSocket honors setSoTimeout; DatagramChannel.receive does not
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            socket.send(new DatagramPacket(queryBytes, queryBytes.length, serverSocket));

            byte[] buf = new byte[BUFFER_SIZE];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            long deadline = queryStartTime + timeoutMs;

            // Ignore packets from other sources or with a wrong ID until the deadline
            while (true) {
                int remaining = (int) (deadline - System.currentTimeMillis());
                if (remaining <= 0) {
                    throw new SocketTimeoutException("No valid response from " + nameserver);
                }
                socket.setSoTimeout(remaining);
                socket.receive(packet);

                boolean fromServer = serverSocket.getAddress().equals(packet.getAddress())
                        && packet.getPort() == 53;
                boolean idMatches = packet.getLength() >= 2
                        && (((buf[0] & 0xFF) << 8) | (buf[1] & 0xFF)) == id;
                if (fromServer && idMatches) {
                    break;
                }
            }

            if (config.isDebugEnabled()) {
                log.info(String.format("Query to %s completed in %d ms",
                        nameserver, System.currentTimeMillis() - queryStartTime));
            }
            DnsResponse response = parseFullResponse(Arrays.copyOf(buf, packet.getLength()), request);
            if (response != null && response.getHeader().tc()) {
                return queryNameserverTcp(queryBytes, serverSocket, request);
            }
            return response;
        }
    }

    /**
     * Retry a truncated UDP query over TCP (2-byte length prefix per RFC 1035)
     */
    private DnsResponse queryNameserverTcp(byte[] queryBytes, InetSocketAddress server, DnsRequest request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(server, timeoutMs);
            socket.setSoTimeout(timeoutMs);
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            out.writeShort(queryBytes.length);
            out.write(queryBytes);
            out.flush();

            DataInputStream in = new DataInputStream(socket.getInputStream());
            byte[] responseBytes = new byte[in.readUnsignedShort()];
            in.readFully(responseBytes);
            return parseFullResponse(responseBytes, request);
        }
    }

    /**
     * Build DNS query bytes
     */
    private byte[] buildQuery(DnsRequest request) {
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
    private DnsResponse parseFullResponse(byte[] data, DnsRequest originalRequest) {
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

    /**
     * Extract referral nameserver addresses from the authority and additional sections.
     * Glue is only trusted when it belongs to an NS record in the authority section.
     */
    private List<String> extractReferralNameservers(DnsResponse response, int depth, ResolutionStats stats) {
        List<String> nsNames = new ArrayList<>();
        if (response.getAuthority() != null) {
            for (DnsRecord record : response.getAuthority()) {
                if (record.type() == 2) { // NS record
                    String nsName = parseDomainFromRdata(record.rdata());
                    if (nsName != null && !nsName.isEmpty()) {
                        nsNames.add(nsName.toLowerCase());
                    }
                }
            }
        }

        List<String> nameservers = new ArrayList<>();
        if (response.getAdditional() != null) {
            for (DnsRecord record : response.getAdditional()) {
                if (record.type() == 1 && nsNames.contains(record.name().toLowerCase())) { // glue A record
                    String ip = formatIPv4(record.rdata());
                    if (ip != null) {
                        nameservers.add(ip);
                    }
                }
            }
        }
        if (!nameservers.isEmpty()) {
            return nameservers;
        }

        // No glue: resolve the NS names ourselves, stopping at the first that resolves.
        // The side lookups must not decide the outcome of the original query.
        boolean definitive = stats.definitive;
        boolean nxdomain = stats.nxdomain;
        for (String nsName : nsNames) {
            for (DnsRecord record : resolveRecursive(nsName, 1, rootServers, depth + 1, stats)) {
                String ip = record.type() == 1 ? formatIPv4(record.rdata()) : null;
                if (ip != null) {
                    nameservers.add(ip);
                }
            }
            if (!nameservers.isEmpty()) break;
        }
        stats.definitive = definitive;
        stats.nxdomain = nxdomain;

        return nameservers;
    }

    // Helper methods (skipDomainName, parseResourceRecord, parseDomainName, etc.)
    // These are similar to the existing implementation

    private int skipDomainName(byte[] data, int position) {
        while (position < data.length) {
            int labelLength = data[position] & 0xFF;
            if (labelLength == 0) return position + 1;
            if ((labelLength & 0xC0) == 0xC0) return position + 2;
            position += labelLength + 1;
        }
        return position;
    }

    private ParseResult parseResourceRecord(byte[] data, int position) {
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
    byte[] expandRdata(byte[] data, int start, int length, int type) {
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

    private void writeName(ByteArrayOutputStream out, String name) {
        if (!name.isEmpty()) {
            for (String label : name.split("\\.")) {
                byte[] bytes = label.getBytes(StandardCharsets.ISO_8859_1);
                out.write(bytes.length);
                out.write(bytes, 0, bytes.length);
            }
        }
        out.write(0);
    }

    NameParseResult parseDomainName(byte[] data, int position) {
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

    private String parseDomainFromRdata(byte[] rdata) {
        if (rdata == null || rdata.length == 0) return null;
        NameParseResult result = parseDomainName(rdata, 0);
        return result.name;
    }

    private String formatIPv4(byte[] rdata) {
        if (rdata == null || rdata.length != 4) return null;
        return String.format("%d.%d.%d.%d",
                rdata[0] & 0xFF, rdata[1] & 0xFF, rdata[2] & 0xFF, rdata[3] & 0xFF);
    }

    /**
     * @return NXDOMAIN, NOERROR (no data) or SERVFAIL (nobody answered) for an empty result
     */
    private int emptyAnswerRcode(ResolutionStats stats) {
        if (!stats.definitive) return 2;
        return stats.nxdomain ? 3 : 0;
    }

    private DnsResponse createErrorResponse(DnsRequest request, int rcode) {
        DnsResponse response = new DnsResponse(request);
        response.setHeader(response.getHeader().withRcode(rcode));
        return response;
    }

    private String getTypeName(int type) {
        return DnsRecord.typeName(type);
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

    /**
     * Shutdown the resolver and cleanup resources
     */
    public void shutdown() {
        log.info("Shutting down recursive DNS resolver...");
        cache.shutdown();
        log.info("Recursive DNS resolver shutdown completed");
    }

    /**
     * Statistics for tracking resolution performance
     */
    private static class ResolutionStats {
        int queriesMade = 0;
        int maxDepthReached = 0;
        boolean definitive = false;  // a server gave an authoritative empty answer
        boolean nxdomain = false;
    }
}
