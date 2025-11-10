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
        // For now, we'll just echo back a simple response
        // In a real implementation, you would parse the DNS query and construct a proper response

        byte[] query = new byte[queryBuffer.remaining()];
        queryBuffer.get(query);

        // Create a basic DNS response (this is a simplified example)
        ByteBuffer responseBuffer = createDnsResponse(query);

        // Send response back to client
        int bytesSent = channel.send(responseBuffer, clientAddress);
        if (config.isDebugEnabled()) {
            LOGGER.info(String.format("Sent %d bytes response to %s:%d",
                    bytesSent, clientAddress.getHostString(), clientAddress.getPort()));
        }
    }

    /**
     * Create a DNS response (simplified - returns SERVFAIL for now)
     */
    private ByteBuffer createDnsResponse(byte[] query) {
        // Copy query to response
        ByteBuffer response = ByteBuffer.allocate(query.length);
        response.put(query);
        response.flip();

        // Modify DNS header to indicate it's a response
        // Set QR bit (query/response) to 1 (response)
        // Set RCODE to 2 (SERVFAIL) since we're not implementing full DNS resolution yet
        if (query.length >= 2) {
            byte flags1 = query[2];
            flags1 |= (byte) 0x80; // Set QR bit to 1 (response)
            response.put(2, flags1);

            byte flags2 = query[3];
            flags2 = (byte) ((flags2 & 0xF0) | 0x02); // Set RCODE to SERVFAIL
            response.put(3, flags2);
        }

        response.rewind();
        return response;
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
