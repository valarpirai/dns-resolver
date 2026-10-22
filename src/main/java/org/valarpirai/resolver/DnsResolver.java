package org.valarpirai.resolver;

import org.valarpirai.protocol.*;
import org.valarpirai.util.Configuration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recursive DNS Resolver
 * Queries upstream DNS servers to resolve domain names
 */
public class DnsResolver {
    private static final Logger LOGGER = Logger.getLogger(DnsResolver.class.getName());
    private static final int TIMEOUT_MS = 5000;
    private static final int BUFFER_SIZE = 4096; // Increased to handle multiple answers

    private final List<String> upstreamServers;
    private final Configuration config;

    public DnsResolver(Configuration config) {
        this.config = config;
        this.upstreamServers = new ArrayList<>();

        // Default upstream DNS servers (Google DNS and Cloudflare)
        String upstreamConfig = config.getString("resolver.upstream.servers", "8.8.8.8,1.1.1.1");
        for (String server : upstreamConfig.split(",")) {
            upstreamServers.add(server.trim());
        }

        LOGGER.info("DNS Resolver initialized with upstream servers: " + upstreamServers);
    }

    /**
     * Resolve a DNS request by querying upstream servers
     */
    public DnsResponse resolve(DnsRequest request) {
        if (request.getQuestions().isEmpty()) {
            return createErrorResponse(request, 1); // Format error
        }

        DnsQuestion question = request.getQuestions().get(0);

        if (config.isDebugEnabled()) {
            LOGGER.info("Resolving: " + question.name() + " (Type: " + question.getTypeName() + ")");
        }

        // Try each upstream server
        for (String upstreamServer : upstreamServers) {
            try {
                DnsResponse response = queryUpstream(request, upstreamServer);
                if (response != null) {
                    if (config.isDebugEnabled()) {
                        LOGGER.info("Resolved " + question.name() + " with " +
                                response.getAnswers().size() + " answer(s) from " + upstreamServer);
                    }
                    return response;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to query upstream server " + upstreamServer, e);
            }
        }

        // All upstream servers failed
        LOGGER.warning("All upstream servers failed for query: " + question.name());
        return createErrorResponse(request, 2); // Server failure
    }

    /**
     * Query an upstream DNS server
     */
    private DnsResponse queryUpstream(DnsRequest request, String upstreamServer) throws IOException {
        DatagramChannel channel = null;

        try {
            // Open UDP channel
            channel = DatagramChannel.open();
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(TIMEOUT_MS);

            // Connect to upstream server
            InetAddress serverAddress = InetAddress.getByName(upstreamServer);
            InetSocketAddress serverSocket = new InetSocketAddress(serverAddress, 53);

            // Send query
            byte[] queryBytes = request.getRawData();
            ByteBuffer sendBuffer = ByteBuffer.wrap(queryBytes);
            channel.send(sendBuffer, serverSocket);

            if (config.isDebugEnabled()) {
                LOGGER.info("Sent query to " + upstreamServer + " (" + queryBytes.length + " bytes)");
            }

            // Receive response
            ByteBuffer receiveBuffer = ByteBuffer.allocate(BUFFER_SIZE);
            InetSocketAddress responseAddress = (InetSocketAddress) channel.receive(receiveBuffer);

            if (responseAddress == null) {
                LOGGER.warning("No response from " + upstreamServer);
                return null;
            }

            receiveBuffer.flip();
            byte[] responseBytes = new byte[receiveBuffer.remaining()];
            receiveBuffer.get(responseBytes);

            if (config.isDebugEnabled()) {
                LOGGER.info("Received response from " + upstreamServer + " (" + responseBytes.length + " bytes)");
            }

            // Parse response
            return parseResponse(responseBytes, request);

        } finally {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        }
    }

    /**
     * Parse DNS response from bytes
     */
    private DnsResponse parseResponse(byte[] data, DnsRequest originalRequest) {
        try {
            if (data.length < 12) {
                throw new IllegalArgumentException("Response too short");
            }

            // Parse header
            DnsHeader header = DnsHeader.builder()
                    .id(((data[0] & 0xFF) << 8) | (data[1] & 0xFF))
                    .qr((data[2] & 0x80) != 0)
                    .opcode((data[2] >> 3) & 0x0F)
                    .aa((data[2] & 0x04) != 0)
                    .tc((data[2] & 0x02) != 0)
                    .rd((data[2] & 0x01) != 0)
                    .ra((data[3] & 0x80) != 0)
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

            // Skip questions section (we already have it from the original request)
            int position = skipQuestions(data, 12, header.qdcount());

            // Parse answer records
            for (int i = 0; i < header.ancount() && position < data.length; i++) {
                try {
                    ParseResult result = parseResourceRecord(data, position);
                    if (result != null) {
                        response.addAnswer(result.record);
                        position = result.newPosition;
                    } else {
                        break;
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to parse answer record " + i, e);
                    break;
                }
            }

            return response;

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to parse DNS response", e);
            return createErrorResponse(originalRequest, 2); // Server failure
        }
    }

    /**
     * Skip questions section in DNS response
     */
    private int skipQuestions(byte[] data, int position, int count) {
        for (int i = 0; i < count && position < data.length; i++) {
            // Skip domain name
            position = skipDomainName(data, position);
            // Skip QTYPE (2 bytes) and QCLASS (2 bytes)
            position += 4;
        }
        return position;
    }

    /**
     * Skip domain name in DNS packet
     */
    private int skipDomainName(byte[] data, int position) {
        while (position < data.length) {
            int labelLength = data[position] & 0xFF;

            if (labelLength == 0) {
                return position + 1;
            }

            if ((labelLength & 0xC0) == 0xC0) {
                // Compressed label (pointer)
                return position + 2;
            }

            position += labelLength + 1;
        }
        return position;
    }

    /**
     * Parse resource record from DNS response
     */
    private ParseResult parseResourceRecord(byte[] data, int position) {
        try {
            // Parse name
            NameParseResult nameResult = parseDomainName(data, position);
            String name = nameResult.name;
            position = nameResult.newPosition;

            if (position + 10 > data.length) {
                return null;
            }

            // Parse type, class, TTL, rdlength
            int type = ((data[position] & 0xFF) << 8) | (data[position + 1] & 0xFF);
            int rclass = ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            int ttl = ((data[position + 4] & 0xFF) << 24) |
                      ((data[position + 5] & 0xFF) << 16) |
                      ((data[position + 6] & 0xFF) << 8) |
                      (data[position + 7] & 0xFF);
            int rdlength = ((data[position + 8] & 0xFF) << 8) | (data[position + 9] & 0xFF);
            position += 10;

            if (position + rdlength > data.length) {
                return null;
            }

            // Extract rdata
            byte[] rdata = new byte[rdlength];
            System.arraycopy(data, position, rdata, 0, rdlength);
            position += rdlength;

            DnsRecord record = DnsRecord.builder()
                    .name(name)
                    .type(type)
                    .rclass(rclass)
                    .ttl(ttl)
                    .rdlength(rdlength)
                    .rdata(rdata)
                    .build();

            return new ParseResult(record, position);

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to parse resource record", e);
            return null;
        }
    }

    /**
     * Parse domain name from DNS packet
     */
    private NameParseResult parseDomainName(byte[] data, int position) {
        StringBuilder name = new StringBuilder();
        int originalPosition = position;
        boolean jumped = false;

        while (position < data.length) {
            int labelLength = data[position] & 0xFF;

            if (labelLength == 0) {
                if (!jumped) {
                    position++;
                }
                break;
            }

            if ((labelLength & 0xC0) == 0xC0) {
                // Compressed label (pointer)
                if (!jumped) {
                    originalPosition = position + 2;
                    jumped = true;
                }
                int pointer = ((labelLength & 0x3F) << 8) | (data[position + 1] & 0xFF);
                position = pointer;
                continue;
            }

            position++;

            if (position + labelLength > data.length) {
                break;
            }

            if (name.length() > 0) {
                name.append('.');
            }

            for (int i = 0; i < labelLength; i++) {
                name.append((char) data[position + i]);
            }

            position += labelLength;
        }

        return new NameParseResult(name.toString(), jumped ? originalPosition : position);
    }

    /**
     * Create error response
     */
    private DnsResponse createErrorResponse(DnsRequest request, int rcode) {
        DnsResponse response = new DnsResponse(request);
        response.setHeader(response.getHeader().withRcode(rcode));
        return response;
    }

    /**
     * Helper class for parse results
     */
    private static class ParseResult {
        final DnsRecord record;
        final int newPosition;

        ParseResult(DnsRecord record, int newPosition) {
            this.record = record;
            this.newPosition = newPosition;
        }
    }

    /**
     * Helper class for name parse results
     */
    private static class NameParseResult {
        final String name;
        final int newPosition;

        NameParseResult(String name, int newPosition) {
            this.name = name;
            this.newPosition = newPosition;
        }
    }
}
