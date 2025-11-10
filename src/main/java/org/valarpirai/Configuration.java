package org.valarpirai;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configuration manager that loads settings from properties file
 * and allows environment variable overrides
 */
public class Configuration {
    private static final Logger LOGGER = Logger.getLogger(Configuration.class.getName());
    private static final String PROPERTIES_FILE = "application.properties";
    private static final String ENV_PREFIX = "DNS_";

    private final Properties properties;

    private Configuration() {
        this.properties = loadProperties();
    }

    /**
     * Load properties from file
     */
    private Properties loadProperties() {
        Properties props = new Properties();

        try (InputStream input = getClass().getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (input == null) {
                LOGGER.warning("Unable to find " + PROPERTIES_FILE + ", using defaults");
                setDefaults(props);
                return props;
            }

            props.load(input);
            LOGGER.info("Loaded configuration from " + PROPERTIES_FILE);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error loading properties file, using defaults", e);
            setDefaults(props);
        }

        return props;
    }

    /**
     * Set default properties
     */
    private void setDefaults(Properties props) {
        props.setProperty("server.port", "53");
        props.setProperty("server.buffer.size", "512");
        props.setProperty("server.selector.timeout", "1000");
        props.setProperty("server.debug", "false");
        props.setProperty("server.bind.address", "0.0.0.0");
    }

    /**
     * Get string property with environment variable override
     * Environment variable format: DNS_<PROPERTY_NAME> (e.g., DNS_SERVER_PORT)
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * Get string property with default value and environment variable override
     */
    public String getString(String key, String defaultValue) {
        // Convert property key to environment variable name
        // e.g., "server.port" -> "DNS_SERVER_PORT"
        String envKey = ENV_PREFIX + key.replace(".", "_").toUpperCase();

        // Check environment variable first
        String envValue = System.getenv(envKey);
        if (envValue != null) {
            LOGGER.info("Using environment variable " + envKey + " = " + envValue);
            return envValue;
        }

        // Fall back to properties file
        return properties.getProperty(key, defaultValue);
    }

    /**
     * Get integer property with environment variable override
     */
    public int getInt(String key, int defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOGGER.warning("Invalid integer value for " + key + ": " + value + ", using default: " + defaultValue);
            return defaultValue;
        }
    }

    /**
     * Get boolean property with environment variable override
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        String value = getString(key);
        if (value == null) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value);
    }

    /**
     * Get server port
     */
    public int getServerPort() {
        return getInt("server.port", 53);
    }

    /**
     * Get buffer size
     */
    public int getBufferSize() {
        return getInt("server.buffer.size", 512);
    }

    /**
     * Get selector timeout
     */
    public int getSelectorTimeout() {
        return getInt("server.selector.timeout", 1000);
    }

    /**
     * Check if debug mode is enabled
     */
    public boolean isDebugEnabled() {
        return getBoolean("server.debug", false);
    }

    /**
     * Get bind address
     */
    public String getBindAddress() {
        return getString("server.bind.address", "0.0.0.0");
    }

    /**
     * Get singleton instance
     */
    public static Configuration getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * Lazy initialization holder class
     */
    private static class InstanceHolder {
        private static final Configuration INSTANCE = new Configuration();
    }

    /**
     * Print current configuration
     */
    public void printConfiguration() {
        LOGGER.info("=== DNS Server Configuration ===");
        LOGGER.info("Server Port: " + getServerPort());
        LOGGER.info("Bind Address: " + getBindAddress());
        LOGGER.info("Buffer Size: " + getBufferSize());
        LOGGER.info("Selector Timeout: " + getSelectorTimeout());
        LOGGER.info("Debug Mode: " + isDebugEnabled());
        LOGGER.info("================================");
    }
}
