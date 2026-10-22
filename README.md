# DNS Resolver

A non-blocking UDP DNS server built with Java NIO for handling DNS query requests.

## Features

- Non-blocking I/O using Java NIO `DatagramChannel` and `Selector`
- Listens on UDP port 53 (configurable)
- **True Recursive DNS Resolution** - Starts from root DNS servers and follows the DNS hierarchy
  - Queries 13 root servers for TLD nameservers
  - Queries TLD servers for authoritative nameservers
  - Queries authoritative servers for final answers
  - Handles CNAME chains, referrals, and glue records
- **TTL-based Caching** - Uses Caffeine for intelligent caching with DNS TTL expiration
- Full DNS query/response parsing with DTOs
- Configuration via properties file with environment variable overrides
- Lombok-powered data classes for clean code
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
| `resolver.root.servers` | `DNS_RESOLVER_ROOT_SERVERS` | `198.41.0.4,...` | Root DNS servers (comma-separated) |
| `resolver.timeout` | `DNS_RESOLVER_TIMEOUT` | `5000` | Query timeout in ms |
| `resolver.max.depth` | `DNS_RESOLVER_MAX_DEPTH` | `16` | Maximum recursion depth |

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
# Test A record (IPv4)
dig @localhost -p 5353 google.com A

# Test AAAA record (IPv6)
dig @localhost -p 5353 google.com AAAA

# Test multiple answers (google.com typically has multiple IPs)
dig @localhost -p 5353 google.com

# Test CNAME record
dig @localhost -p 5353 www.github.com

# Test MX record (mail servers)
dig @localhost -p 5353 gmail.com MX

# Using nslookup
nslookup example.com localhost
```

### Multiple Answer Support

The DNS resolver fully supports **multiple DNS answers** in responses:

- **Multiple A records**: Domains with multiple IPv4 addresses (load balancing, CDN)
- **Multiple AAAA records**: Domains with multiple IPv6 addresses
- **Multiple MX records**: Mail servers with different priorities
- **Multiple NS records**: Nameservers for a domain

**Debug Output Example (Recursive Resolution):**
```
DNS Query from 127.0.0.1:12345
  Question: example.com (Type: A, Class: IN)
=== Starting recursive resolution for example.com (Type: A) ===
Cache MISS: example.com A
[Depth 0] Querying for example.com (Type: A) using 13 nameserver(s)
[Depth 0] Got referral to 13 nameserver(s)
[Depth 1] Querying for example.com (Type: A) using 13 nameserver(s)
[Depth 1] Got referral to 2 nameserver(s)
[Depth 2] Querying for example.com (Type: A) using 2 nameserver(s)
[Depth 2] Got 1 answer(s) from 199.43.135.53
Cached: example.com A (1 record(s), TTL: 86400s)
Response: 1 answer(s), RCODE: 0
;; ANSWER SECTION:
example.com                    86400  IN    A      93.184.216.34
Sent 128 bytes response to 127.0.0.1:12345
```

The resolver shows:
1. **Root query** (Depth 0): Gets TLD nameservers for .com
2. **TLD query** (Depth 1): Gets authoritative nameservers for example.com
3. **Authoritative query** (Depth 2): Gets final A record
4. **Caching**: Stores result with TTL from DNS response

## Project Structure

```
dns-resolver/
├── src/
│   └── main/
│       ├── java/
│       │   └── org/
│       │       └── valarpirai/
│       │           ├── Main.java                   # Application entry point
│       │           ├── DnsServer.java              # Non-blocking DNS server
│       │           ├── RecursiveDnsResolver.java   # True recursive DNS resolver
│       │           ├── DnsCache.java               # Caffeine-based TTL cache
│       │           ├── Configuration.java          # Configuration manager
│       │           ├── DnsHeader.java              # DNS header DTO
│       │           ├── DnsQuestion.java            # DNS question DTO
│       │           ├── DnsRequest.java             # DNS request DTO
│       │           ├── DnsResponse.java            # DNS response DTO
│       │           └── DnsRecord.java              # DNS record DTO
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
- **True Recursive Resolution**: Starts from root servers, not forwarding to other resolvers
- **DNS Hierarchy Traversal**:
  1. Queries root servers (a.root-servers.net through m.root-servers.net)
  2. Follows referrals to TLD nameservers (.com, .org, etc.)
  3. Queries TLD servers for authoritative nameservers
  4. Gets final answer from authoritative servers
- **Intelligent Caching**: Caffeine-based cache respects DNS TTL values
- **CNAME Resolution**: Automatically follows CNAME chains
- **Glue Record Support**: Uses additional section for faster resolution
- Parses full DNS responses (answers, authority, additional sections)
- Supports all standard query types (A, AAAA, CNAME, MX, TXT, NS, PTR, etc.)
- Dynamic buffer sizing to handle large responses
- Detailed debug logging showing resolution path

## Future Enhancements

- Full DNS query parsing
- DNS resolution logic
- Caching layer
- Support for different record types (A, AAAA, CNAME, MX, etc.)
- Forwarding to upstream DNS servers
- Metrics and monitoring

## License

This project is open source and available under the MIT License.
