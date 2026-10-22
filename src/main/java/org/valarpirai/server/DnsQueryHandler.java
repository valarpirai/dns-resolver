package org.valarpirai.server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.valarpirai.protocol.*;
import org.valarpirai.resolver.RecursiveDnsResolver;
import org.valarpirai.util.Configuration;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

/**
 * Parses one query, resolves it and sends the reply. Runs on a worker thread.
 */
@Slf4j
@RequiredArgsConstructor
class DnsQueryHandler {
    private final DatagramChannel channel;
    private final RecursiveDnsResolver resolver;
    private final Configuration config;

    /**
     * Process DNS query and send response
     */
    void handle(byte[] query, InetSocketAddress clientAddress) {
        long requestStartTime = System.currentTimeMillis();
        DnsRequest request = null;

        try {
            // Parse DNS request using DTO
            long parseStartTime = System.currentTimeMillis();
            request = DnsRequest.parse(query);
            long parseTime = System.currentTimeMillis() - parseStartTime;

            // Never answer a response packet: two servers would ping-pong, and it enables reflection
            if (request.getHeader().qr()) {
                return;
            }

            // Resolve the query using recursive resolver
            long resolveStartTime = System.currentTimeMillis();
            DnsResponse response = request.getHeader().opcode() != 0
                    ? errorResponse(request, 4) // NOTIMP
                    : resolver.resolve(request);
            long resolveTime = System.currentTimeMillis() - resolveStartTime;

            if (config.isDebugEnabled()) {
                log.info(String.format("Response: %d answer(s), RCODE: %d",
                        response.getAnswers().size(), response.getHeader().rcode()));

                if (!response.getAnswers().isEmpty()) {
                    log.info(";; ANSWER SECTION:");
                    for (DnsRecord answer : response.getAnswers()) {
                        log.info(String.format("%-30s %-6d %-5s %-6s %s",
                                answer.name(),
                                answer.ttl(),
                                "IN",
                                answer.getTypeName(),
                                DnsLogFormat.recordData(answer)));
                    }
                }
            }

            // Convert response to bytes
            long serializeStartTime = System.currentTimeMillis();
            byte[] responseBytes = response.toBytes(request.getUdpPayloadSize());
            ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
            long serializeTime = System.currentTimeMillis() - serializeStartTime;

            // Send response back to client
            long sendStartTime = System.currentTimeMillis();
            int bytesSent = channel.send(responseBuffer, clientAddress);
            long sendTime = System.currentTimeMillis() - sendStartTime;

            long totalTime = System.currentTimeMillis() - requestStartTime;

            // Get query name for logging
            String queryName = request.getQuestions().isEmpty() ? "UNKNOWN" : request.getQuestions().get(0).name();
            String queryType = request.getQuestions().isEmpty() ? "?" : request.getQuestions().get(0).getTypeName();

            // Always log request summary with timing and statistics
            String cacheStatus = response.isCacheHit() ? "HIT" : "MISS";
            log.info(String.format("%s %s | %d ms (parse:%d resolve:%d serialize:%d send:%d) | cache:%s queries:%d depth:%d answers:%d | %s:%d",
                    queryName, queryType,
                    totalTime, parseTime, resolveTime, serializeTime, sendTime,
                    cacheStatus, response.getQueriesMade(), response.getMaxDepthReached(),
                    response.getAnswers().size(),
                    clientAddress.getHostString(), clientAddress.getPort()));

            if (config.isDebugEnabled()) {
                log.info(String.format("Sent %d bytes response to %s:%d",
                        bytesSent, clientAddress.getHostString(), clientAddress.getPort()));
            }
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - requestStartTime;
            int rcode = e instanceof DnsException de ? de.getRcode() : DnsException.SERVFAIL;
            if (e instanceof DnsException) {
                log.warn("Bad DNS query from " + clientAddress + ": " + e.getMessage());
            } else {
                log.error("Error processing DNS query from " + clientAddress + " after " + totalTime + " ms", e);
            }
            if (request != null) {
                try {
                    channel.send(ByteBuffer.wrap(errorResponse(request, rcode).toBytes()), clientAddress);
                } catch (Exception sendError) {
                    log.debug("Could not send error response to " + clientAddress, sendError);
                }
            }
        }
    }

    private DnsResponse errorResponse(DnsRequest request, int rcode) {
        DnsResponse response = new DnsResponse(request);
        response.setHeader(response.getHeader().withRcode(rcode));
        return response;
    }
}
