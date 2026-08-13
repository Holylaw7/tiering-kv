package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多组织联邦仲裁（ADR-0249）：cloud → organization 映射；组织内云多数 →
 * 组织合格；组织多数 → 联邦一阶段；任一组织不合格回退 2PC。
 */
public final class MultiOrgFederationArbitration {

    public record FederationResult(String txnId, boolean onePhase,
                                   boolean succeeded,
                                   int organizations,
                                   int eligibleOrganizations,
                                   int eligibleClouds) {
    }

    private final Map<String, String> cloudOrganization =
            new ConcurrentHashMap<>();
    private final Map<String, Map<String, Boolean>> zoneEligibility =
            new ConcurrentHashMap<>();
    private final Map<String, FederationResult> completed =
            new ConcurrentHashMap<>();
    private volatile ResolvedTimestampService resolvedTs;
    private volatile long federationVersion;

    public void registerOrganization(String cloud,
                                     String organization) {
        if (cloud == null || cloud.isBlank()
                || organization == null
                || organization.isBlank()) {
            throw new IllegalArgumentException(
                    "cloud and organization required");
        }
        cloudOrganization.put(cloud, organization);
        federationVersion++;
        completed.clear();
    }

    public void registerZone(String cloud, String zone,
                             boolean eligible) {
        zoneEligibility.computeIfAbsent(cloud,
                ignored -> new ConcurrentHashMap<>())
                .put(zone, eligible);
        federationVersion++;
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

    /** 联邦一阶段：组织内云多数 → 组织合格；组织多数 → 一阶段。 */
    public FederationResult commit(String txnId,
                                   Set<String> clouds) {
        return commit(txnId, clouds, Long.MIN_VALUE);
    }

    public FederationResult commit(String txnId, Set<String> clouds,
                                   long commitTs) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
        if (clouds == null || clouds.isEmpty()) {
            throw new IllegalArgumentException(
                    "clouds required");
        }
        long version = federationVersion;
        String cacheKey = txnId + "|v" + version + "|"
                + clouds.stream().sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
        FederationResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Map<String, Long> orgCloudCount = new ConcurrentHashMap<>();
        Map<String, Long> orgEligibleCount = new ConcurrentHashMap<>();
        int eligibleClouds = 0;
        for (String cloud : clouds) {
            Map<String, Boolean> zones = zoneEligibility
                    .getOrDefault(cloud, Map.of());
            long zoneEligible = zones.values().stream()
                    .filter(Boolean::booleanValue).count();
            if (zones.size() > 0
                    && zoneEligible > zones.size() / 2) {
                eligibleClouds++;
                String org = cloudOrganization.getOrDefault(
                        cloud, "default");
                orgCloudCount.merge(org, 1L, Long::sum);
                orgEligibleCount.merge(org, 1L, Long::sum);
            } else {
                String org = cloudOrganization.getOrDefault(
                        cloud, "default");
                orgCloudCount.merge(org, 1L, Long::sum);
            }
        }
        long eligibleOrgs = orgEligibleCount.entrySet().stream()
                .filter(entry -> entry.getValue()
                        > orgCloudCount.getOrDefault(
                        entry.getKey(), 0L) / 2)
                .count();
        long totalOrgs = orgCloudCount.keySet().stream()
                .filter(org -> cloudOrganization.containsValue(org)
                        || org.equals("default"))
                .count();
        boolean quorum = eligibleOrgs > totalOrgs / 2;
        FederationResult result = new FederationResult(txnId,
                quorum, true, (int) totalOrgs,
                (int) eligibleOrgs, eligibleClouds);
        completed.putIfAbsent(cacheKey, result);
        if (quorum && commitTs != Long.MIN_VALUE
                && resolvedTs != null) {
            resolvedTs.advance(commitTs);
        }
        return completed.get(cacheKey);
    }

    public long federationVersion() {
        return federationVersion;
    }
}
