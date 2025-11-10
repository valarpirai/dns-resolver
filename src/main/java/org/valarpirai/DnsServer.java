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
        byte[] query = new byte[queryBuffer.remaining()];
        queryBuffer.get(query);

        try {
            // Parse DNS request using DTO
            DnsRequest request = DnsRequest.parse(query);

            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("DNS Query from %s:%d", clientAddress.getHostString(), clientAddress.getPort()));
                for (DnsQuestion question : request.getQuestions()) {
                    LOGGER.info(String.format("  Question: %s (Type: %s, Class: %s)",
                            question.getName(), question.getTypeName(), question.getClassName()));
                }
            }

            // Create a basic DNS response (returns SERVFAIL for now)
            DnsResponse response = new DnsResponse(request);
            response.getHeader().setRcode(2); // SERVFAIL

            // Convert response to bytes
            byte[] responseBytes = response.toBytes();
            ByteBuffer responseBuffer = ByteBuffer.wrap(responseBytes);

            // Send response back to client
            int bytesSent = channel.send(responseBuffer, clientAddress);
            if (config.isDebugEnabled()) {
                LOGGER.info(String.format("Sent %d bytes response to %s:%d",
                        bytesSent, clientAddress.getHostString(), clientAddress.getPort()));
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error processing DNS query from " + clientAddress, e);
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
