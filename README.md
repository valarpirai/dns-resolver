# DNS Resolver

A non-blocking UDP DNS server built with Java NIO for handling DNS query requests.

## Features

- Non-blocking I/O using Java NIO `DatagramChannel` and `Selector`
- Listens on UDP port 53 (configurable)
- Handles DNS query requests
- Configuration via properties file with environment variable overrides
- Docker containerization support
- Executable JAR packaging

## Prerequisites

- Java 21 (Azul Zulu recommended)
- Maven 3.9+
- Docker (optional, for containerization)

## Building the Application

### Using Maven

Build the executable JAR:

```bash
mvn clean package
```

Or use the custom task:

```bash
mvn exec:exec@dns-build
```

The executable JAR will be created at `target/dns-resolver.jar`

## Configuration

The application can be configured via:

1. **Properties file**: `src/main/resources/application.properties`
2. **Environment variables**: Override properties with `DNS_` prefix

### Available Configuration Options

| Property | Environment Variable | Default | Description |
|----------|---------------------|---------|-------------|
| `server.port` | `DNS_SERVER_PORT` | `53` | UDP port to listen on |
| `server.bind.address` | `DNS_SERVER_BIND_ADDRESS` | `0.0.0.0` | Address to bind to |
| `server.buffer.size` | `DNS_SERVER_BUFFER_SIZE` | `512` | Buffer size in bytes |
| `server.selector.timeout` | `DNS_SERVER_SELECTOR_TIMEOUT` | `1000` | Selector timeout in ms |
| `server.debug` | `DNS_SERVER_DEBUG` | `false` | Enable debug logging |

### Configuration Examples

Using environment variables:

```bash
export DNS_SERVER_PORT=5353
export DNS_SERVER_DEBUG=true
java -jar target/dns-resolver.jar
```

Or inline:

```bash
DNS_SERVER_PORT=5353 DNS_SERVER_DEBUG=true java -jar target/dns-resolver.jar
```

## Running the Application

### Running Locally

Run with default configuration (port 53):

```bash
sudo java -jar target/dns-resolver.jar
```

Note: Port 53 requires root/administrator privileges. To use an alternate port:

```bash
DNS_SERVER_PORT=5353 java -jar target/dns-resolver.jar
```

### Using Docker

Build the Docker image:

```bash
docker build -t dns-resolver .
```

Run the container:

```bash
docker run -p 53:53/udp dns-resolver
```

Or with environment variable configuration:

```bash
docker run -p 5353:5353/udp \
  -e DNS_SERVER_PORT=5353 \
  -e DNS_SERVER_DEBUG=true \
  dns-resolver
```

Using a .env file:

```bash
docker run -p 53:53/udp --env-file .env dns-resolver
```

### Docker Compose (Optional)

Create a `docker-compose.yml`:

```yaml
version: '3.8'
services:
  dns-resolver:
    build: .
    ports:
      - "53:53/udp"
    environment:
      - DNS_SERVER_PORT=53
      - DNS_SERVER_DEBUG=false
    # Or use env_file:
    # env_file:
    #   - .env
    restart: unless-stopped
```

Run with:

```bash
docker-compose up -d
```

## Testing the DNS Server

You can test the DNS server using `dig` or `nslookup`:

```bash
# Using dig
dig @localhost -p 53 example.com

# Using nslookup
nslookup example.com localhost
```

If running on a custom port:

```bash
dig @localhost -p 5353 example.com
```

## Project Structure

```
dns-resolver/
├── src/
│   └── main/
│       ├── java/
│       │   └── org/
│       │       └── example/
│       │           ├── Main.java           # Application entry point
│       │           ├── DnsServer.java      # Non-blocking DNS server
│       │           └── Configuration.java  # Configuration manager
│       └── resources/
│           └── application.properties      # Default configuration
├── pom.xml                                 # Maven configuration
├── Dockerfile                              # Docker image definition
├── .dockerignore                           # Docker build exclusions
├── .env.example                            # Example environment variables
└── README.md                               # This file
```

## Architecture

The DNS server uses Java NIO's non-blocking I/O model:

1. **DatagramChannel**: Non-blocking UDP channel for receiving DNS queries
2. **Selector**: Multiplexes multiple channels for efficient event handling
3. **Event Loop**: Processes incoming DNS queries asynchronously
4. **Graceful Shutdown**: Handles SIGTERM/SIGINT for clean server shutdown

## Current Implementation

The current implementation:
- Receives DNS queries on UDP port 53
- Logs incoming requests
- Returns a basic DNS response (SERVFAIL for now)

## Future Enhancements

- Full DNS query parsing
- DNS resolution logic
- Caching layer
- Support for different record types (A, AAAA, CNAME, MX, etc.)
- Forwarding to upstream DNS servers
- Metrics and monitoring

## License

This project is open source and available under the MIT License.
