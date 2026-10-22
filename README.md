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

## Key Dependencies

- **Lombok 1.18.30**: Reduces boilerplate code with @Data, @Builder annotations (compile-time only)
- **Caffeine 3.1.8**: High-performance caching library with custom TTL-based expiry and memory-weighted eviction
- **JUnit 5.10.1**: Testing framework for unit and integration tests
- **Maven Shade Plugin**: Creates executable JAR with all dependencies

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
| `cache.max.entries` | `DNS_CACHE_MAX_ENTRIES` | `10000` | Maximum cache entries |
| `cache.max.memory` | `DNS_CACHE_MAX_MEMORY` | `10485760` | Maximum memory (10MB) |
| `cache.min.ttl` | `DNS_CACHE_MIN_TTL` | `10` | Minimum TTL in seconds |
| `cache.stats.interval` | `DNS_CACHE_STATS_INTERVAL` | `300` | Stats logging interval in seconds |

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

## Running Tests

The project includes comprehensive test coverage with **1,930+ lines of test code**.

Run all tests:

```bash
mvn test
```

Run specific test class:

```bash
mvn test -Dtest=RecursiveDnsResolverIntegrationTest
```

### Test Coverage

- **Protocol Tests**: DNS header, question, record, request, and response parsing
- **Cache Tests**: TTL-based expiration, memory limits, statistics tracking (312 lines)
- **Resolver Tests**: Recursive resolution, CNAME handling, glue records (329 lines)
- **Server Tests**: Non-blocking I/O, query processing, graceful shutdown (320 lines)

## Project Structure

```
dns-resolver/
├── src/
│   ├── main/
│   │   ├── java/org/valarpirai/
│   │   │   ├── server/
│   │   │   │   ├── Main.java                    # Entry point
│   │   │   │   └── DnsServer.java               # Non-blocking NIO server
│   │   │   ├── resolver/
│   │   │   │   ├── RecursiveDnsResolver.java    # True recursive resolver
│   │   │   │   └── DnsResolver.java             # Forwarding resolver (unused)
│   │   │   ├── cache/
│   │   │   │   └── DnsCache.java                # Caffeine-based cache
│   │   │   ├── protocol/
│   │   │   │   ├── DnsHeader.java               # DNS header DTO
│   │   │   │   ├── DnsQuestion.java             # Question DTO
│   │   │   │   ├── DnsRequest.java              # Request DTO
│   │   │   │   ├── DnsResponse.java             # Response DTO
│   │   │   │   └── DnsRecord.java               # Resource record DTO
│   │   │   └── util/
│   │   │       ├── Configuration.java           # Config manager
│   │   │       └── SingleLineFormatter.java     # Log formatter
│   │   └── resources/
│   │       └── application.properties            # Default config
│   └── test/
│       └── java/org/valarpirai/
│           ├── cache/
│           │   └── DnsCacheIntegrationTest.java
│           ├── protocol/
│           │   ├── DnsHeaderTest.java
│           │   ├── DnsQuestionTest.java
│           │   ├── DnsRecordTest.java
│           │   ├── DnsRequestTest.java
│           │   └── DnsResponseTest.java
│           ├── resolver/
│           │   └── RecursiveDnsResolverIntegrationTest.java
│           └── server/
│               └── DnsServerIntegrationTest.java
├── pom.xml                                       # Maven configuration
├── Dockerfile                                    # Docker image
├── docker-compose.yml                            # Docker Compose
├── .dockerignore                                 # Docker exclusions
├── .env.example                                  # Example env vars
└── README.md                                     # This file
```

## Architecture

### High-Level Architecture

```
┌──────────────────────────────────────────────────────┐
│                    DNS Client                         │
│                  (dig, nslookup)                      │
└────────────────────┬─────────────────────────────────┘
                     │ UDP Query (Port 53)
                     ▼
┌──────────────────────────────────────────────────────┐
│                   DnsServer                           │
│          (Non-blocking Java NIO)                      │
│  • DatagramChannel (UDP)                              │
│  • Selector (Event Multiplexing)                      │
│  • Event Loop                                         │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│              RecursiveDnsResolver                     │
│  • Check DnsCache first                               │
│  • If miss, start recursive resolution                │
└────────────────────┬─────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────────────────┐
│          True Recursive Resolution Flow               │
│                                                        │
│  1. Query Root Servers (13 roots)                     │
│     └─► Get TLD nameservers (.com, .org, etc.)       │
│                                                        │
│  2. Query TLD Servers                                 │
│     └─► Get Authoritative nameservers                 │
│                                                        │
│  3. Query Authoritative Servers                       │
│     └─► Get final answer (A, AAAA, CNAME, etc.)      │
│                                                        │
│  • Handle CNAME chains                                │
│  • Use glue records when available                    │
│  • Cache results with TTL                             │
└──────────────────────────────────────────────────────┘
```

### Data Flow

1. **Query Reception**: Non-blocking DatagramChannel receives UDP DNS query
2. **Parsing**: DnsRequest.parse() converts bytes to structured DTO
3. **Cache Lookup**: DnsCache checks for cached response with TTL validation
4. **Resolution**: If cache miss, RecursiveDnsResolver performs iterative queries starting from root servers
5. **Response Building**: DnsResponse constructed with answers, authority, and additional sections
6. **Caching**: Results stored in Caffeine cache with DNS TTL-based expiration
7. **Serialization**: DnsResponse.toBytes() converts to network format
8. **Transmission**: Response sent back to client via DatagramChannel

### Key Components

- **Non-blocking I/O**: DatagramChannel with Selector for concurrent query handling
- **Event Loop**: Continuously processes ready channels with configurable timeout (1000ms)
- **Graceful Shutdown**: Signal handlers (SIGTERM/SIGINT) for clean server termination
- **Memory Management**: Weighted cache eviction based on actual memory usage (10MB default)

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

- DNSSEC validation for secure DNS resolution
- Rate limiting and DDoS protection
- Prometheus metrics export for monitoring
- Persistent cache storage (Redis/disk-based)
- Query logging and analytics
- Support for DNS-over-TLS (DoT) and DNS-over-HTTPS (DoH)
- IPv6 transport support
- Zone file loading for authoritative responses

## License

This project is open source and available under the MIT License.
