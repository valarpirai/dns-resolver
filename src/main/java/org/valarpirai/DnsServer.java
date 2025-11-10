package org.valarpirai;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking UDP DNS Server using Java NIO
 */
public class DnsServer {
    private static final Logger LOGGER = Logger.getLogger(DnsServer.class.getName());

    private final Configuration config;
    private final RecursiveDnsResolver resolver;
    private final int port;
    private final String bindAddress;
    private final int bufferSize;
    private final int selectorTimeout;
    private DatagramChannel channel;
    private Selector selector;
    private volatile boolean running;

    public DnsServer() {
        this(Configuration.getInstance());
    }

    public DnsServer(Configuration config) {
        this.config = config;
        this.resolver = new RecursiveDnsResolver(config);
        this.port = config.getServerPort();
        this.bindAddress = config.getBindAddress();
        this.bufferSize = config.getBufferSize();
        this.selectorTimeout = config.getSelectorTimeout();
        this.running = false;
    }

    /**
     * Start the DNS server
     */
    public void start() throws IOException {
        LOGGER.info("Starting DNS server on " + bindAddress + ":" + port);

        // Open a non-blocking DatagramChannel
        channel = DatagramChannel.open();
        channel.configureBlocking(false);

        // Bind to specified address and port
        InetSocketAddress address = new InetSocketAddress(bindAddress, port);
        channel.socket().bind(address);

        // Create a selector for multiplexing
        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);

        running = true;
        LOGGER.info("DNS server started successfully on " + bindAddress + ":" + port);
        if (config.isDebugEnabled()) {
            LOGGER.info("Debug mode is enabled");
        }

        // Main event loop
        eventLoop();
    }

    /**
     * Non-blocking event loop to process DNS queries
     */
    private void eventLoop() throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

        while (running) {
            try {
                // Wait for events (configurable timeout)
                int readyChannels = selector.select(selectorTimeout);

                if (readyChannels == 0) {
                    continue;
                }

                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (key.isReadable()) {
                        handleRead(buffer);
                    }
                }
            } catch (IOException e) {
                if (running) {
                    LOGGER.log(Level.SEVERE, "Error in event loop", e);
                }
            }
        }
    }

    /**
     * Handle incoming DNS query
     */
    private void handleRead(ByteBuffer buffer) throws IOException {
        buffer.clear();

        // Receive DNS query
        InetSocketAddress clientAddress = (InetSocketAddress) channel.receive(buffer);

        if (clientAddress == null) {
            return;
        }

        buffer.flip();
        int bytesReceived = buffer.remaining();

        if (config.isDebugEnabled()) {
            LOGGER.info(String.format("Received %d bytes from %s:%d",
                    bytesReceived, clientAddress.getHostString(), clientAddress.getPort()));

            // Log the raw data (first 12 bytes is DNS header)
            if (bytesReceived >= 12) {
                byte[] data = new byte[Math.min(bytesReceived, 50)];
                buffer.get(data);
                LOGGER.info("DNS Query Header (hex): " + bytesToHex(data));
                buffer.rewind();
            }
        }

        // Process the DNS query and send response
        processDnsQuery(buffer, clientAddress);
    }

    /**
     * Process DNS query and send response
     */
    private void processDnsQuery(ByteBuffer queryBuffer, InetSocketAddress clientAddress) throws IOException {
        long requestStartTime = System.currentTimeMillis();

        byte[] query = new byte[queryBuffer.remaining()];
        queryBuffer.get(query);

        try {
            // Parse DNS request using DTO
            long parseStartTime = System.currentTimeMillis();
            DnsRequest request = DnsRequest.parse(query);
            long parseTime = System.currentTimeMillis() - parseStartTime;

            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("DNS Query from %s:%d", clientAddress.getHostString(), clientAddress.getPort()));
                for (DnsQuestion question : request.getQuestions()) {
                    LOGGER.info(String.format("  Question: %s (Type: %s, Class: %s)",
                            question.name(), question.getTypeName(), question.getClassName()));
                }
            }

            // Resolve the query using recursive resolver
            long resolveStartTime = System.currentTimeMillis();
            DnsResponse response = resolver.resolve(request);
            long resolveTime = System.currentTimeMillis() - resolveStartTime;

            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("Response: %d answer(s), RCODE: %d",
                        response.getAnswers().size(), response.getHeader().rcode()));

                if (!response.getAnswers().isEmpty()) {
                    LOGGER.info(";; ANSWER SECTION:");
                    for (DnsRecord answer : response.getAnswers()) {
                        LOGGER.info(String.format("%-30s %-6d %-5s %-6s %s",
                                answer.name(),
                                answer.ttl(),
                                "IN",
                                answer.getTypeName(),
                                formatRecordData(answer)));
                    }
                }
            }

            // Convert response to bytes
            long serializeStartTime = System.currentTimeMillis();
            byte[] responseBytes = response.toBytes();
            ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);
            long serializeTime = System.currentTimeMillis() - serializeStartTime;

            // Send response back to client
            long sendStartTime = System.currentTimeMillis();
            int bytesSent = channel.send(responseBuffer, clientAddress);
            long sendTime = System.currentTimeMillis() - sendStartTime;

            long totalTime = System.currentTimeMillis() - requestStartTime;

            // Always log request summary with timing and statistics
            String cacheStatus = response.isCacheHit() ? "CACHE HIT" : "CACHE MISS";
            LOGGER.info(String.format("Request completed in %d ms (parse: %d ms, resolve: %d ms, serialize: %d ms, send: %d ms) | %s | queries: %d | depth: %d | answers: %d | %s:%d",
                    totalTime, parseTime, resolveTime, serializeTime, sendTime,
                    cacheStatus, response.getQueriesMade(), response.getMaxDepthReached(),
                    response.getAnswers().size(),
                    clientAddress.getHostString(), clientAddress.getPort()));

            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("Sent %d bytes response to %s:%d",
                        bytesSent, clientAddress.getHostString(), clientAddress.getPort()));
            }
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - requestStartTime;
            LOGGER.log(Level.WARNING, "Error processing DNS query from " + clientAddress + " after " + totalTime + " ms", e);
        }
    }

    /**
     * Stop the DNS server
     */
    public void stop() {
        LOGGER.info("Stopping DNS server...");
        running = false;

        try {
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            LOGGER.info("DNS server stopped successfully");
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Error stopping DNS server", e);
        }
    }

    /**
     * Utility method to convert bytes to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02X ", b));
        }
        return result.toString().trim();
    }

    /**
     * Format DNS record data for logging
     */
    private String formatRecordData(DnsRecord record) {
        if (record.rdata() == null || record.rdata().length == 0) {
            return "empty";
        }

        return switch (record.type()) {
            case 1 -> {  // A record (IPv4)
                if (record.rdata().length == 4) {
                    yield String.format("%d.%d.%d.%d",
                            record.rdata()[0] & 0xFF,
                            record.rdata()[1] & 0xFF,
                            record.rdata()[2] & 0xFF,
                            record.rdata()[3] & 0xFF);
                }
                yield bytesToHex(record.rdata());
            }
            case 28 -> { // AAAA record (IPv6)
                if (record.rdata().length == 16) {
                    StringBuilder ipv6 = new StringBuilder();
                    for (int i = 0; i < 16; i += 2) {
                        if (i > 0) ipv6.append(":");
                        ipv6.append(String.format("%02x%02x",
                                record.rdata()[i] & 0xFF,
                                record.rdata()[i + 1] & 0xFF));
                    }
                    yield ipv6.toString();
                }
                yield bytesToHex(record.rdata());
            }
            case 5, 2, 12 -> { // CNAME, NS, PTR (domain names)
                try {
                    StringBuilder name = new StringBuilder();
                    int pos = 0;
                    while (pos < record.rdata().length) {
                        int len = record.rdata()[pos] & 0xFF;
                        if (len == 0) break;
                        if ((len & 0xC0) == 0xC0) break; // Pointer
                        pos++;
                        if (name.length() > 0) name.append(".");
                        for (int i = 0; i < len && pos < record.rdata().length; i++) {
                            name.append((char) record.rdata()[pos++]);
                        }
                    }
                    yield name.toString();
                } catch (Exception e) {
                    yield bytesToHex(record.rdata());
                }
            }
            case 16 -> { // TXT record
                try {
                    StringBuilder txt = new StringBuilder("\"");
                    int pos = 0;
                    while (pos < record.rdata().length) {
                        int len = record.rdata()[pos++] & 0xFF;
                        for (int i = 0; i < len && pos < record.rdata().length; i++) {
                            txt.append((char) record.rdata()[pos++]);
                        }
                    }
                    txt.append("\"");
                    yield txt.toString();
                } catch (Exception e) {
                    yield bytesToHex(record.rdata());
                }
            }
            default -> bytesToHex(record.rdata());
        };
    }

    /**
     * Check if server is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the port the server is listening on
     */
    public int getPort() {
        return port;
    }
}
