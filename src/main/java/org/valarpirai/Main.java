package org.valarpirai;

import java.io.IOException;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        // Load configuration (from properties file + environment variables)
        Configuration config = Configuration.getInstance();

        // Print configuration
        config.printConfiguration();

        // Create DNS server with configuration
        DnsServer server = new DnsServer(config);

        // Add shutdown hook for graceful termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown signal received");
            server.stop();
        }));

        try {
            LOGGER.info("DNS Resolver Server starting...");
            server.start();
        } catch (IOException e) {
            LOGGER.severe("Failed to start DNS server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}