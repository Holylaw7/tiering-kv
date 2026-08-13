package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨云一阶段规模化（ADR-0235）：云 × 区混合拓扑，分层仲裁
 * （区内多数 → 云级合格；云级多数 → 跨云一阶段）。
 */
public final class MultiCloudOnePhaseScaleOut {

    /** 分层仲裁结果。 */
    public record ScaleOutResult(String txnId, boolean onePhase,
                                 boolean succeeded,
                                 int clouds, int eligibleClouds,
                                 int zones, int eligibleZones) {
    }

    private final Map<String, Map<String, Boolean>> zoneEligibility =
            new ConcurrentHashMap<>();
    private final Map<String, ScaleOutResult> completed =
            new ConcurrentHashMap<>();
    private volatile ResolvedTimestampService resolvedTs;

    /** 注册区资格：cloud → zone → 是否一阶段合格。 */
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
        completed.clear();
    }

    /** 标记云不可用：其所有区降级。 */
    public void markCloudUnavailable(String cloud) {
        Map<String, Boolean> zones = zoneEligibility.get(cloud);
        if (zones != null) {
            zones.replaceAll((zone, eligible) -> false);
        }
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

    /** 跨云一阶段：分层仲裁（区内多数 → 云合格；云多数 → 一阶段）。 */
    public ScaleOutResult commit(String txnId,
                                 Map<String, Set<String>> topology) {
        return commit(txnId, topology, Long.MIN_VALUE);
    }

    public ScaleOutResult commit(String txnId,
                                 Map<String, Set<String>> topology,
                                 long commitTs) {
        validate(txnId, topology);
        String cacheKey = cacheKey(txnId, topology);
        ScaleOutResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        int eligibleClouds = 0;
        int totalZones = 0;
        int eligibleZones = 0;
        for (Map.Entry<String, Set<String>> cloud
                : topology.entrySet()) {
            Map<String, Boolean> zones = zoneEligibility
                    .getOrDefault(cloud.getKey(), Map.of());
            int zoneTotal = cloud.getValue().size();
            long zoneEligible = cloud.getValue().stream()
                    .filter(zone -> zones.getOrDefault(zone,
                            false))
                    .count();
            totalZones += zoneTotal;
            eligibleZones += zoneEligible;
            if (zoneEligible > zoneTotal / 2) {
                eligibleClouds++;
            }
        }
        boolean quorum = eligibleClouds > topology.size() / 2;
        ScaleOutResult result = new ScaleOutResult(txnId,
                quorum, true, topology.size(), eligibleClouds,
                totalZones, eligibleZones);
        completed.putIfAbsent(cacheKey, result);
        if (quorum && commitTs != Long.MIN_VALUE
                && resolvedTs != null) {
            resolvedTs.advance(commitTs);
        }
        return completed.get(cacheKey);
    }

    private static void validate(
            String txnId, Map<String, Set<String>> topology) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
        if (topology == null || topology.isEmpty()) {
            throw new IllegalArgumentException(
                    "topology required");
        }
        if (topology.values().stream()
                .anyMatch(zones -> zones == null
                        || zones.isEmpty())) {
            throw new IllegalArgumentException(
                    "each cloud requires zones");
        }
    }

    private static String cacheKey(
            String txnId, Map<String, Set<String>> topology) {
        String topo = topology.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "="
                        + entry.getValue().stream().sorted()
                        .reduce((a, b) -> a + "," + b)
                        .orElse(""))
                .reduce((a, b) -> a + ";" + b).orElse("");
        return txnId + "|" + topo;
    }
}
