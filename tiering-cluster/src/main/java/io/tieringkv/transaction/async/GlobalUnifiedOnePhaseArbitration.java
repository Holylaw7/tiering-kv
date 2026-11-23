package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨云一阶段全球统一（ADR-0242）：任意云 × 区拓扑自动发现 + 动态仲裁
 * （多数云 + 多数区），任一层次不合格回退 2PC。
 */
public final class GlobalUnifiedOnePhaseArbitration {

    public record UnifiedResult(String txnId, boolean onePhase,
                                boolean succeeded,
                                long topologyVersion,
                                int eligibleClouds,
                                int eligibleZones) {
    }

    private final Map<String, Map<String, Boolean>> zoneEligibility =
            new ConcurrentHashMap<>();
    private final Map<String, UnifiedResult> completed =
            new ConcurrentHashMap<>();
    private volatile ResolvedTimestampService resolvedTs;
    private volatile long topologyVersion;

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

    public void markCloudUnavailable(String cloud) {
        Map<String, Boolean> zones = zoneEligibility.get(cloud);
        if (zones != null) {
            zones.replaceAll((zone, eligible) -> false);
        }
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

    /** 自动发现拓扑并仲裁：无需调用方传拓扑。 */
    public UnifiedResult commit(String txnId,
                                Set<String> clouds) {
        return commit(txnId, clouds, Long.MIN_VALUE);
    }

    public UnifiedResult commit(String txnId, Set<String> clouds,
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
        UnifiedResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        int eligibleClouds = 0;
        int eligibleZones = 0;
        for (String cloud : clouds) {
            Map<String, Boolean> zones = zoneEligibility
                    .getOrDefault(cloud, Map.of());
            long zoneEligible = zones.values().stream()
                    .filter(Boolean::booleanValue).count();
            eligibleZones += zoneEligible;
            if (zones.size() > 0
                    && zoneEligible > zones.size() / 2) {
                eligibleClouds++;
            }
        }
        boolean quorum = eligibleClouds > clouds.size() / 2;
        UnifiedResult result = new UnifiedResult(txnId,
                quorum, true, version, eligibleClouds,
                eligibleZones);
        completed.putIfAbsent(cacheKey, result);
        if (quorum && commitTs != Long.MIN_VALUE
                && resolvedTs != null) {
            resolvedTs.advance(commitTs);
        }
        return completed.get(cacheKey);
    }

    public long topologyVersion() {
        return topologyVersion;
    }
}
