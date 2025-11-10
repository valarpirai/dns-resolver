package org.valarpirai;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * True Recursive DNS Resolver
 * Starts from root servers and follows the DNS hierarchy
 */
public class RecursiveDnsResolver {
    private static final Logger LOGGER = Logger.getLogger(RecursiveDnsResolver.class.getName());
    private static final int BUFFER_SIZE = 4096;

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

        LOGGER.info(String.format("Recursive DNS Resolver initialized with %d root servers, timeout: %d ms",
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
            LOGGER.info(String.format("=== Starting recursive resolution for %s (Type: %s) ===",
                    qname, question.getTypeName()));
        }

        // Check cache first
        long cacheStartTime = System.currentTimeMillis();
        List<DnsRecord> cachedRecords = cache.get(qname, qtype);
        if (cachedRecords != null) {
            long cacheTime = System.currentTimeMillis() - cacheStartTime;
            long totalTime = System.currentTimeMillis() - startTime;

            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("Resolution completed in %d ms (cache: %d ms)",
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
                LOGGER.info(String.format("Resolution completed in %d ms (cache lookup: %d ms, recursive resolve: %d ms, queries made: %d, max depth: %d)",
                        totalTime, cacheTime, resolveTime, stats.queriesMade, stats.maxDepthReached));
            }

            DnsResponse response = new DnsResponse(request);
            response.setHeader(response.getHeader()
                    .withRcode(answers.isEmpty() ? 3 : 0)  // NXDOMAIN or NOERROR
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

        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            LOGGER.log(Level.SEVERE, "Recursive resolution failed for " + qname + " after " + totalTime + " ms", e);
            return createErrorResponse(request, 2); // Server failure
        }
    }

    /**
     * Recursive resolution following DNS hierarchy
     */
    private List<DnsRecord> resolveRecursive(String qname, int qtype, List<String> nameservers, int depth, ResolutionStats stats) {
        stats.maxDepthReached = Math.max(stats.maxDepthReached, depth);

        if (depth > maxDepth) {
            LOGGER.warning("Max recursion depth reached for " + qname);
            return new ArrayList<>();
        }

        if (config.isDebugEnabled()) {
            LOGGER.info(String.format("[Depth %d] Querying for %s (Type: %s) using %d nameserver(s)",
                    depth, qname, getTypeName(qtype), nameservers.size()));
        }

        // Try each nameserver
        for (String ns : nameservers) {
            try {
                stats.queriesMade++;
                DnsResponse response = queryNameserver(qname, qtype, ns);
                if (response == null) continue;

                // Check if we got answers
                if (!response.getAnswers().isEmpty()) {
                    if (config.isDebugEnabled()) {
                        LOGGER.info(String.format("[Depth %d] Got %d answer(s) from %s",
                                depth, response.getAnswers().size(), ns));
                    }

                    // Handle CNAME records
                    DnsRecord firstAnswer = response.getAnswers().get(0);
                    if (firstAnswer.type() == 5 && qtype != 5) { // CNAME
                        String cname = parseDomainFromRdata(firstAnswer.rdata());
                        if (cname != null && !cname.isEmpty()) {
                            if (config.isDebugEnabled()) {
                                LOGGER.info(String.format("[Depth %d] Following CNAME: %s -> %s",
                                        depth, qname, cname));
                            }
                            // Recursively resolve the CNAME
                            List<DnsRecord> cnameAnswers = resolveRecursive(cname, qtype, rootServers, depth + 1, stats);
                            // Combine CNAME with final answers
                            List<DnsRecord> allAnswers = new ArrayList<>(response.getAnswers());
                            allAnswers.addAll(cnameAnswers);
                            return allAnswers;
                        }
                    }

                    return response.getAnswers();
                }

                // Check for referral (authority/additional sections with NS records)
                List<String> referralNS = extractReferralNameservers(response);
                if (!referralNS.isEmpty()) {
                    if (config.isDebugEnabled()) {
                        LOGGER.info(String.format("[Depth %d] Got referral to %d nameserver(s)",
                                depth, referralNS.size()));
                    }
                    return resolveRecursive(qname, qtype, referralNS, depth + 1, stats);
                }

            } catch (SocketTimeoutException e) {
                LOGGER.warning(String.format("Nameserver %s timed out after %d ms", ns, timeoutMs));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to query nameserver " + ns, e);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Query a specific nameserver
     */
    private DnsResponse queryNameserver(String qname, int qtype, String nameserver) throws IOException {
        DatagramChannel channel = null;

        try {
            // Create DNS query
            DnsRequest request = DnsRequest.builder()
                    .header(DnsHeader.builder()
                            .id((int) (Math.random() * 65535))
                            .qr(false)
                            .rd(false) // No recursion desired for iterative queries
                            .qdcount(1)
                            .build())
                    .build();

            DnsQuestion question = DnsQuestion.builder()
                    .name(qname)
                    .type(qtype)
                    .qclass(1) // IN
                    .build();
            request.addQuestion(question);

            // Convert to bytes
            byte[] queryBytes = buildQuery(request);

            // Send query
            long queryStartTime = System.currentTimeMillis();
            channel = DatagramChannel.open();
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(timeoutMs);

            InetAddress serverAddress = InetAddress.getByName(nameserver);
            InetSocketAddress serverSocket = new InetSocketAddress(serverAddress, 53);

            ByteBuffer sendBuffer = ByteBuffer.wrap(queryBytes);
            channel.send(sendBuffer, serverSocket);

            // Receive response
            ByteBuffer receiveBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            InetSocketAddress responseAddress = (InetSocketAddress) channel.receive(receiveBuffer);

            long queryTime = System.currentTimeMillis() - queryStartTime;
            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("Query to %s completed in %d ms", nameserver, queryTime));
            }

            if (responseAddress == null) {
                LOGGER.warning(String.format("No response from %s (timed out after %d ms)", nameserver, timeoutMs));
                return null;
            }

            receiveBuffer.flip();
            byte[] responseBytes = new byte[receiveBuffer.remaining()];
            receiveBuffer.get(responseBytes);

            // Parse response
            return parseFullResponse(responseBytes, request);

        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * Build DNS query bytes
     */
    private byte[] buildQuery(DnsRequest request) {
        ByteBuffer buffer = ByteBuffer.allocate(512);

        // Header
        buffer.putShort((short) request.getHeader().id());
        buffer.put((byte) 0x01); // Recursion desired
        buffer.put((byte) 0x00);
        buffer.putShort((short) 1); // 1 question
        buffer.putShort((short) 0); // 0 answers
        buffer.putShort((short) 0); // 0 authority
        buffer.putShort((short) 0); // 0 additional

        // Question
        DnsQuestion question = request.getQuestions().get(0);
        String[] labels = question.name().split("\\.");
        for (String label : labels) {
            buffer.put((byte) label.length());
            buffer.put(label.getBytes());
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
            LOGGER.log(Level.WARNING, "Failed to parse DNS response", e);
            return null;
        }
    }

    /**
     * Extract referral nameservers from authority and additional sections
     */
    private List<String> extractReferralNameservers(DnsResponse response) {
        List<String> nameservers = new ArrayList<>();

        // First try to get IPs from additional section (glue records)
        if (response.getAdditional() != null) {
            for (DnsRecord record : response.getAdditional()) {
                if (record.type() == 1) { // A record
                    String ip = formatIPv4(record.rdata());
                    if (ip != null) {
                        nameservers.add(ip);
                    }
                }
            }
        }

        // If we have glue records, use them
        if (!nameservers.isEmpty()) {
            return nameservers;
        }

        // Otherwise, resolve NS records from authority section
        if (response.getAuthority() != null) {
            for (DnsRecord record : response.getAuthority()) {
                if (record.type() == 2) { // NS record
                    String nsName = parseDomainFromRdata(record.rdata());
                    if (nsName != null && !nsName.isEmpty()) {
                        // Try to resolve the NS name to IP
                        try {
                            InetAddress addr = InetAddress.getByName(nsName);
                            nameservers.add(addr.getHostAddress());
                        } catch (Exception e) {
                            // If we can't resolve it, try the next one
                        }
                    }
                }
            }
        }

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

            byte[] rdata = new byte[rdlength];
            System.arraycopy(data, position, rdata, 0, rdlength);
            position += rdlength;

            DnsRecord record = DnsRecord.builder()
                    .name(nameResult.name)
                    .type(type)
                    .rclass(rclass)
                    .ttl(ttl)
                    .rdlength(rdlength)
                    .rdata(rdata)
                    .build();

            return new ParseResult(record, position);

        } catch (Exception e) {
            return null;
        }
    }

    private NameParseResult parseDomainName(byte[] data, int position) {
        StringBuilder name = new StringBuilder();
        int originalPosition = position;
        boolean jumped = false;

        while (position < data.length) {
            int labelLength = data[position] & 0xFF;

            if (labelLength == 0) {
                if (!jumped) position++;
                break;
            }

            if ((labelLength & 0xC0) == 0xC0) {
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

    private DnsResponse createErrorResponse(DnsRequest request, int rcode) {
        DnsResponse response = new DnsResponse(request);
        response.setHeader(response.getHeader().withRcode(rcode));
        return response;
    }

    private String getTypeName(int type) {
        return switch (type) {
            case 1 -> "A";
            case 2 -> "NS";
            case 5 -> "CNAME";
            case 28 -> "AAAA";
            default -> "TYPE_" + type;
        };
    }

    private static class ParseResult {
        final DnsRecord record;
        final int newPosition;

        ParseResult(DnsRecord record, int newPosition) {
            this.record = record;
            this.newPosition = newPosition;
        }
    }

    private static class NameParseResult {
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
        LOGGER.info("Shutting down recursive DNS resolver...");
        cache.shutdown();
        LOGGER.info("Recursive DNS resolver shutdown completed");
    }

    /**
     * Statistics for tracking resolution performance
     */
    private static class ResolutionStats {
        int queriesMade = 0;
        int maxDepthReached = 0;
    }
}
