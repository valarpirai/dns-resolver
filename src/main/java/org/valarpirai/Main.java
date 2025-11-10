package org.valarpirai;

import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        // Configure single-line logging
        configureSingleLineLogging();

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

    /**
     * Configure logging to use single-line format
     */
    private static void configureSingleLineLogging() {
        try {
            // Get root logger
            Logger rootLogger = LogManager.getLogManager().getLogger("");

            // Remove all existing handlers
            for (Handler handler : rootLogger.getHandlers()) {
                rootLogger.removeHandler(handler);
            }

            // Create and configure console handler with single-line formatter
            ConsoleHandler consoleHandler = new ConsoleHandler();
            consoleHandler.setLevel(Level.ALL);
            consoleHandler.setFormatter(new SingleLineFormatter());

            // Add the new handler to root logger
            rootLogger.addHandler(consoleHandler);
            rootLogger.setLevel(Level.INFO);

        } catch (Exception e) {
            System.err.println("Failed to configure logging: " + e.getMessage());
        }
    }
}