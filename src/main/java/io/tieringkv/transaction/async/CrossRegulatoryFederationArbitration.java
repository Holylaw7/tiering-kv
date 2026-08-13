package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨监管域联邦仲裁（ADR-0256）：cloud → regulatory domain 边界发现 +
 * 域内多数 → 域合格；任一域不合格回退 2PC；幂等结果缓存。
 */
public final class CrossRegulatoryFederationArbitration {

    public record DomainResult(String txnId, boolean onePhase,
                               boolean succeeded,
                               boolean fallback2Pc,
                               int domains, int eligibleDomains,
                               int eligibleClouds,
                               long topologyVersion) {
    }

    private final Map<String, String> cloudDomain =
            new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> zoneEligibility =
            new ConcurrentHashMap<>();
    private final Map<String, DomainResult> completed =
            new ConcurrentHashMap<>();
    private volatile ResolvedTimestampService resolvedTs;
    private volatile MultiOrgFederationArbitration multiOrg;
    private volatile GlobalUnifiedOnePhaseArbitration global;
    private volatile long topologyVersion;

    /** 监管域边界发现：cloud → domain。 */
    public void registerDomain(String cloud, String domain) {
        if (cloud == null || cloud.isBlank()
                || domain == null || domain.isBlank()) {
            throw new IllegalArgumentException(
                    "cloud and domain required");
        }
        cloudDomain.put(cloud, domain);
        topologyVersion++;
        completed.clear();
    }

    public void registerZone(String cloud, String zone,
                             boolean eligible) {
        if (cloud == null || cloud.isBlank()
                || zone == null || zone.isBlank()) {
            throw new IllegalArgumentException(
                    "cloud and zone required");
        }
        zoneEligibility.computeIfAbsent(cloud,
                ignored -> new ConcurrentHashMap<>())
                .put(zone, eligible);
        topologyVersion++;
        completed.clear();
    }

    public void attachResolvedTimestamp(
            ResolvedTimestampService service) {
        if (service == null) {
            throw new IllegalArgumentException(
                    "service required");
        }
        this.resolvedTs = service;
    }

    public void attachMultiOrg(
            MultiOrgFederationArbitration arbitration) {
        if (arbitration == null) {
            throw new IllegalArgumentException(
                    "arbitration required");
        }
        this.multiOrg = arbitration;
    }

    public void attachGlobal(
            GlobalUnifiedOnePhaseArbitration arbitration) {
        if (arbitration == null) {
            throw new IllegalArgumentException(
                    "arbitration required");
        }
        this.global = arbitration;
    }

    /** 跨域仲裁：任一域不合格回退 2PC。 */
    public DomainResult commit(String txnId, Set<String> clouds) {
        return commit(txnId, clouds, Long.MIN_VALUE);
    }

    public DomainResult commit(String txnId, Set<String> clouds,
                               long commitTs) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
        if (clouds == null || clouds.isEmpty()) {
            throw new IllegalArgumentException(
                    "clouds required");
        }
        long version = topologyVersion;
        String cacheKey = txnId + "|v" + version + "|"
                + clouds.stream().sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
        DomainResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Map<String, Long> domainCloudCount =
                new ConcurrentHashMap<>();
        Map<String, Long> domainEligibleCount =
                new ConcurrentHashMap<>();
        int eligibleClouds = 0;
        for (String cloud : clouds) {
            String domain = cloudDomain.getOrDefault(
                    cloud, "default");
            Map<String, Boolean> zones = zoneEligibility
                    .getOrDefault(cloud, Map.of());
            long zoneEligible = zones.values().stream()
                    .filter(Boolean::booleanValue).count();
            boolean cloudEligible = zones.size() > 0
                    && zoneEligible > zones.size() / 2;
            if (cloudEligible) {
                eligibleClouds++;
            }
            domainCloudCount.merge(domain, 1L, Long::sum);
            if (cloudEligible) {
                domainEligibleCount.merge(domain, 1L,
                        Long::sum);
            }
        }
        long eligibleDomains = domainEligibleCount.entrySet()
                .stream()
                .filter(entry -> entry.getValue()
                        > domainCloudCount.getOrDefault(
                        entry.getKey(), 0L) / 2)
                .count();
        long totalDomains = domainCloudCount.size();
        boolean allDomainsEligible =
                eligibleDomains == totalDomains;
        boolean onePhase = allDomainsEligible;
        boolean externalFallback = false;
        if (multiOrg != null) {
            var orgResult = multiOrg.commit(txnId, clouds,
                    commitTs == Long.MIN_VALUE
                            ? Long.MIN_VALUE : commitTs);
            externalFallback = !orgResult.onePhase();
        }
        if (global != null) {
            var globalResult = global.commit(txnId, clouds,
                    commitTs == Long.MIN_VALUE
                            ? Long.MIN_VALUE : commitTs);
            externalFallback = externalFallback
                    || !globalResult.onePhase();
        }
        boolean fallback2Pc = !onePhase || externalFallback;
        if (onePhase && commitTs != Long.MIN_VALUE
                && resolvedTs != null) {
            resolvedTs.advance(commitTs);
        }
        DomainResult result = new DomainResult(txnId, onePhase,
                true, fallback2Pc, (int) totalDomains,
                (int) eligibleDomains, eligibleClouds, version);
        completed.putIfAbsent(cacheKey, result);
        return result;
    }

    public String domainOf(String cloud) {
        return cloudDomain.getOrDefault(cloud, "default");
    }

    public long topologyVersion() {
        return topologyVersion;
    }

    public int completedCount() {
        return completed.size();
    }
}
