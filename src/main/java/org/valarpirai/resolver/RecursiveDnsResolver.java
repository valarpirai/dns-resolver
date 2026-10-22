package org.valarpirai.resolver;

import lombok.extern.slf4j.Slf4j;
import org.valarpirai.protocol.*;
import org.valarpirai.util.Configuration;
import org.valarpirai.cache.DnsCache;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * True Recursive DNS Resolver
 * Starts from root servers and follows the DNS hierarchy
 */
@Slf4j
public class RecursiveDnsResolver {
    private final List<String> rootServers;
    private final DnsCache cache;
    private final Configuration config;
    private final int maxDepth;
    private final int timeoutMs;
    private final UpstreamClient upstream;

    public RecursiveDnsResolver(Configuration config) {
        this.config = config;
        this.cache = new DnsCache(config);
        this.rootServers = new ArrayList<>();
        this.maxDepth = config.getInt("resolver.max.depth", 16);
        this.timeoutMs = config.getInt("resolver.timeout", 5000);
        this.upstream = new UpstreamClient(timeoutMs, config.isDebugEnabled());

        // Load root servers
        String rootConfig = config.getString("resolver.root.servers",
                "198.41.0.4,199.9.14.201,192.33.4.12");
        for (String server : rootConfig.split(",")) {
            rootServers.add(server.trim());
        }

        log.info(String.format("Recursive DNS Resolver initialized with %d root servers, timeout: %d ms",
                rootServers.size(), timeoutMs));
    }

    /**
     * Resolve a DNS request recursively starting from root servers
     */
    public DnsResponse resolve(DnsRequest request) {
        long startTime = System.currentTimeMillis();

        if (request.getQuestions().isEmpty()) {
            return createErrorResponse(request, 1); // Format error
        }

        DnsQuestion question = request.getQuestions().get(0);
        String qname = question.name();
        int qtype = question.type();

        if (config.isDebugEnabled()) {
            log.info(String.format("=== Starting recursive resolution for %s (Type: %s) ===",
                    qname, question.getTypeName()));
        }

        // Check cache first
        long cacheStartTime = System.currentTimeMillis();
        List<DnsRecord> cachedRecords = cache.get(qname, qtype);
        if (cachedRecords != null) {
            long cacheTime = System.currentTimeMillis() - cacheStartTime;
            long totalTime = System.currentTimeMillis() - startTime;

            if (config.isDebugEnabled()) {
                log.info(String.format("Resolution completed in %d ms (cache: %d ms)",
                        totalTime, cacheTime));
            }

            DnsResponse response = new DnsResponse(request);
            response.setHeader(response.getHeader().withRcode(0).withRa(true));
            response.setCacheHit(true);
            response.setQueriesMade(0);
            response.setMaxDepthReached(0);
            for (DnsRecord record : cachedRecords) {
                response.addAnswer(record);
            }
            return response;
        }
        long cacheTime = System.currentTimeMillis() - cacheStartTime;

        // Start recursive resolution from root
        try {
            long resolveStartTime = System.currentTimeMillis();
            ResolutionStats stats = new ResolutionStats();
            List<DnsRecord> answers = resolveRecursive(qname, qtype, rootServers, 0, stats);
            long resolveTime = System.currentTimeMillis() - resolveStartTime;
            long totalTime = System.currentTimeMillis() - startTime;

            if (config.isDebugEnabled()) {
                log.info(String.format("Resolution completed in %d ms (cache lookup: %d ms, recursive resolve: %d ms, queries made: %d, max depth: %d)",
                        totalTime, cacheTime, resolveTime, stats.queriesMade, stats.maxDepthReached));
            }

            DnsResponse response = new DnsResponse(request);
            response.setHeader(response.getHeader()
                    .withRcode(answers.isEmpty() ? emptyAnswerRcode(stats) : 0)
                    .withRa(true));
            response.setCacheHit(false);
            response.setQueriesMade(stats.queriesMade);
            response.setMaxDepthReached(stats.maxDepthReached);

            for (DnsRecord answer : answers) {
                response.addAnswer(answer);
            }

            // Cache the result
            if (!answers.isEmpty()) {
                cache.put(qname, qtype, answers);
            }

            return response;

        } catch (DnsException e) {
            log.warn("Resolution failed for " + qname + ": " + e.getMessage());
            return createErrorResponse(request, e.getRcode());
        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - startTime;
            log.error("Recursive resolution failed for " + qname + " after " + totalTime + " ms", e);
            return createErrorResponse(request, 2); // Server failure
        }
    }

    /**
     * Recursive resolution following DNS hierarchy
     */
    private List<DnsRecord> resolveRecursive(String qname, int qtype, List<String> nameservers, int depth, ResolutionStats stats) {
        stats.maxDepthReached = Math.max(stats.maxDepthReached, depth);

        if (depth > maxDepth) {
            log.warn("Max recursion depth reached for " + qname);
            return new ArrayList<>();
        }

        if (config.isDebugEnabled()) {
            log.info(String.format("[Depth %d] Querying for %s (Type: %s) using %d nameserver(s)",
                    depth, qname, getTypeName(qtype), nameservers.size()));
        }

        // Try each nameserver
        for (String ns : nameservers) {
            try {
                stats.queriesMade++;
                DnsResponse response = upstream.query(qname, qtype, ns);
                if (response == null) continue;

                int rcode = response.getHeader().rcode();
                if (rcode != 0 && rcode != 3) continue; // SERVFAIL, REFUSED etc: try the next server

                if (!response.getAnswers().isEmpty()) {
                    if (config.isDebugEnabled()) {
                        log.info(String.format("[Depth %d] Got %d answer(s) from %s",
                                depth, response.getAnswers().size(), ns));
                    }

                    List<DnsRecord> answers = response.getAnswers();
                    String cnameTarget = qtype == 5 ? null : unresolvedCnameTarget(qname, qtype, answers);
                    if (cnameTarget == null) {
                        return answers;
                    }

                    if (config.isDebugEnabled()) {
                        log.info(String.format("[Depth %d] Following CNAME: %s -> %s", depth, qname, cnameTarget));
                    }
                    List<DnsRecord> allAnswers = new ArrayList<>(answers);
                    allAnswers.addAll(resolveRecursive(cnameTarget, qtype, rootServers, depth + 1, stats));
                    return allAnswers;
                }

                // Check for referral (authority/additional sections with NS records)
                List<String> referralNS = extractReferralNameservers(response, depth, stats);
                if (!referralNS.isEmpty()) {
                    if (config.isDebugEnabled()) {
                        log.info(String.format("[Depth %d] Got referral to %d nameserver(s)",
                                depth, referralNS.size()));
                    }
                    // The child zone's servers were already all tried; siblings would return the same referral
                    return resolveRecursive(qname, qtype, referralNS, depth + 1, stats);
                }

                // Authoritative empty answer: NXDOMAIN (rcode 3) or NODATA (rcode 0)
                stats.definitive = true;
                stats.nxdomain = rcode == 3;
                return new ArrayList<>();

            } catch (SocketTimeoutException e) {
                log.warn(String.format("Nameserver %s timed out after %d ms", ns, timeoutMs));
            } catch (Exception e) {
                log.warn("Failed to query nameserver " + ns, e);
            }
        }

        return new ArrayList<>();
    }

    /**
     * Follow the CNAME chain in an answer set
     *
     * @param qname   the name that was asked for
     * @param qtype   the type that was asked for
     * @param answers answer records from one upstream response
     * @return the name still to be resolved, or null if the answers already hold the wanted type
     */
    private String unresolvedCnameTarget(String qname, int qtype, List<DnsRecord> answers) {
        String current = qname;
        String target = null;
        for (int hops = 0; hops <= maxDepth; hops++) {
            final String name = current;
            DnsRecord cname = answers.stream()
                    .filter(r -> r.type() == 5 && r.name().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (cname == null) break;
            String next = DnsWire.parseDomainFromRdata(cname.rdata());
            if (next == null || next.isEmpty()) break;
            target = current = next;
        }
        if (target == null) return null;

        // The upstream server may already have included the final records
        final String last = target;
        boolean resolved = answers.stream().anyMatch(r -> r.type() == qtype && r.name().equalsIgnoreCase(last));
        return resolved ? null : target;
    }

    /**
     * Extract referral nameserver addresses from the authority and additional sections.
     * Glue is only trusted when it belongs to an NS record in the authority section.
     */
    private List<String> extractReferralNameservers(DnsResponse response, int depth, ResolutionStats stats) {
        List<String> nsNames = new ArrayList<>();
        if (response.getAuthority() != null) {
            for (DnsRecord record : response.getAuthority()) {
                if (record.type() == 2) { // NS record
                    String nsName = DnsWire.parseDomainFromRdata(record.rdata());
                    if (nsName != null && !nsName.isEmpty()) {
                        nsNames.add(nsName.toLowerCase());
                    }
                }
            }
        }

        List<String> nameservers = new ArrayList<>();
        if (response.getAdditional() != null) {
            for (DnsRecord record : response.getAdditional()) {
                if (record.type() == 1 && nsNames.contains(record.name().toLowerCase())) { // glue A record
                    String ip = formatIPv4(record.rdata());
                    if (ip != null) {
                        nameservers.add(ip);
                    }
                }
            }
        }
        if (!nameservers.isEmpty()) {
            return nameservers;
        }

        // No glue: resolve the NS names ourselves, stopping at the first that resolves.
        // The side lookups must not decide the outcome of the original query.
        boolean definitive = stats.definitive;
        boolean nxdomain = stats.nxdomain;
        for (String nsName : nsNames) {
            for (DnsRecord record : resolveRecursive(nsName, 1, rootServers, depth + 1, stats)) {
                String ip = record.type() == 1 ? formatIPv4(record.rdata()) : null;
                if (ip != null) {
                    nameservers.add(ip);
                }
            }
            if (!nameservers.isEmpty()) break;
        }
        stats.definitive = definitive;
        stats.nxdomain = nxdomain;

        return nameservers;
    }

    private String formatIPv4(byte[] rdata) {
        if (rdata == null || rdata.length != 4) return null;
        return String.format("%d.%d.%d.%d",
                rdata[0] & 0xFF, rdata[1] & 0xFF, rdata[2] & 0xFF, rdata[3] & 0xFF);
    }

    /**
     * @return NXDOMAIN, NOERROR (no data) or SERVFAIL (nobody answered) for an empty result
     */
    private int emptyAnswerRcode(ResolutionStats stats) {
        if (!stats.definitive) return 2;
        return stats.nxdomain ? 3 : 0;
    }

    private DnsResponse createErrorResponse(DnsRequest request, int rcode) {
        DnsResponse response = new DnsResponse(request);
        response.setHeader(response.getHeader().withRcode(rcode));
        return response;
    }

    private String getTypeName(int type) {
        return DnsRecord.typeName(type);
    }

    /**
     * Shutdown the resolver and cleanup resources
     */
    public void shutdown() {
        log.info("Shutting down recursive DNS resolver...");
        cache.shutdown();
        log.info("Recursive DNS resolver shutdown completed");
    }

    /**
     * Statistics for tracking resolution performance
     */
    private static class ResolutionStats {
        int queriesMade = 0;
        int maxDepthReached = 0;
        boolean definitive = false;  // a server gave an authoritative empty answer
        boolean nxdomain = false;
    }
}
