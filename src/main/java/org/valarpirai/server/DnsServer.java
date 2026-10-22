package org.valarpirai.server;

import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import org.valarpirai.util.Configuration;
import org.valarpirai.resolver.RecursiveDnsResolver;
import org.valarpirai.protocol.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Non-blocking UDP DNS Server using Java NIO
 */
@Slf4j
public class DnsServer {

    private final Configuration config;
    private final RecursiveDnsResolver resolver;

    @Getter
    private final int port;
    private final String bindAddress;
    private final int bufferSize;
    private final int selectorTimeout;
    private DatagramChannel channel;
    private DnsQueryHandler handler;
    private Selector selector;
    private final ExecutorService workers;

    @Getter
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

        // Resolution blocks on upstream I/O, so it runs off the event loop; excess queries are dropped (UDP)
        int threads = config.getInt("server.worker.threads", 32);
        this.workers = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.DiscardPolicy());
    }

    /**
     * Start the DNS server
     */
    public void start() throws IOException {
        log.info("Starting DNS server on " + bindAddress + ":" + port);

        // Open a non-blocking DatagramChannel
        channel = DatagramChannel.open();
        channel.configureBlocking(false);

        // Bind to specified address and port
        InetSocketAddress address = new InetSocketAddress(bindAddress, port);
        channel.socket().bind(address);

        // Create a selector for multiplexing
        selector = Selector.open();
        channel.register(selector, SelectionKey.OP_READ);

        handler = new DnsQueryHandler(channel, resolver, config);
        running = true;
        log.info("DNS server started successfully on " + bindAddress + ":" + port);
        if (config.isDebugEnabled()) {
            log.info("Debug mode is enabled");
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
                    log.error("Error in event loop", e);
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
            log.info(String.format("Received %d bytes from %s:%d",
                    bytesReceived, clientAddress.getHostString(), clientAddress.getPort()));

            // Log the raw data (first 12 bytes is DNS header)
            if (bytesReceived >= 12) {
                byte[] data = new byte[Math.min(bytesReceived, 50)];
                buffer.get(data);
                log.info("DNS Query Header (hex): " + DnsLogFormat.bytesToHex(data));
                buffer.rewind();
            }
        }

        // Copy out of the shared buffer, then resolve and reply on a worker thread
        byte[] query = new byte[buffer.remaining()];
        buffer.get(query);
        workers.execute(() -> handler.handle(query, clientAddress));
    }

    /**
     * Stop the DNS server
     */
    public void stop() {
        log.info("Stopping DNS server...");
        running = false;
        workers.shutdownNow();

        try {
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
            if (channel != null && channel.isOpen()) {
                channel.close();
            }

            // Shutdown resolver and cache
            if (resolver != null) {
                resolver.shutdown();
            }

            log.info("DNS server stopped successfully");
        } catch (IOException e) {
            log.error("Error stopping DNS server", e);
        }
    }
}
