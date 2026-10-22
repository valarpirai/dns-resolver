package org.valarpirai.server;

import lombok.extern.slf4j.Slf4j;
import org.valarpirai.util.Configuration;

import java.io.IOException;

@Slf4j
public class Main {

    public static void main(String[] args) {
        // Load configuration (from properties file + environment variables)
        Configuration config = Configuration.getInstance();

        // Print configuration
        config.printConfiguration();

        // Create DNS server with configuration
        DnsServer server = new DnsServer(config);

        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            server.stop();
        }));

        try {
            log.info("DNS Resolver Server starting...");
            server.start();
        } catch (IOException e) {
            log.error("Failed to start DNS server", e);
            System.exit(1);
        }
    }
}
