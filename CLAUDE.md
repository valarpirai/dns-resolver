# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

- Build the shaded JAR: `mvn clean package` (output: `target/dns-resolver.jar`)
- All tests: `mvn test`
- One test class: `mvn test -Dtest=RecursiveDnsResolverIntegrationTest`
- Run on an unprivileged port: `DNS_SERVER_PORT=5353 java -jar target/dns-resolver.jar` (default port 53 needs root)
- Try it: `dig @localhost -p 5353 google.com A`
- Docker: `docker build -t dns-resolver .`

Java 21, Maven 3.9+. Spotless runs `check` in `verify`; fix with `mvn spotless:apply` (it only trims whitespace and unused imports).

## Architecture

A UDP DNS server that does true recursive resolution from the root servers. It does not forward to an upstream resolver.

Request flow (package `org.valarpirai`):

1. `server/DnsServer` runs a single-threaded NIO receive loop (`DatagramChannel` + `Selector`) and hands each packet to a worker pool (`server.worker.threads`, bounded queue, overflow dropped). Workers parse with `DnsRequest.parse()`.
2. `resolver/RecursiveDnsResolver` checks `cache/DnsCache` first. On a miss it queries the root servers, then follows referrals (using glue records) and CNAME chains down to the authoritative answer. Depth is capped by `resolver.max.depth`.
3. The result is cached with the minimum record TTL, then `DnsResponse.toBytes()` serializes it back to the client.

Upstream queries use `DatagramSocket` (not `DatagramChannel`) because only the socket honors `setSoTimeout`. Replies are checked for source address and transaction ID, and truncated replies are retried over TCP.

Things that span files:

- `protocol/*` holds the wire-format DTOs (`DnsHeader`, `DnsQuestion`, `DnsRecord`, `DnsRequest`, `DnsResponse`). Each handles its own parse and serialize, so wire-format changes touch several of them plus their tests.
- `DnsCache` is Caffeine with per-entry TTL expiry and memory-weighted eviction (`cache.max.memory`). Answers below `cache.min.ttl` are not cached, and cached TTLs count down on each hit.
- Names inside record data (NS, CNAME, MX, PTR, SOA, SRV) are decompressed when a reply is parsed, so cached records stay valid outside the packet they came from.
- `util/Configuration` loads `src/main/resources/application.properties`. Each key can be overridden by a `DNS_` env var (`server.port` becomes `DNS_SERVER_PORT`; dots become underscores). The full key list is in `README.md`.
- Lombok is compile-time only, so IDEs need annotation processing enabled.

Tests under `src/test` mirror the main packages. `RecursiveDnsResolverIntegrationTest` queries the real root servers, so it needs network access.
