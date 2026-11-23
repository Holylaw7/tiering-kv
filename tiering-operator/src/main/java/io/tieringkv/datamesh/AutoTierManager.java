package io.tieringkv.datamesh;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 远端物化自动分层（ADR-0187）：热度 → HOT/WARM/COLD。 */
public final class AutoTierManager {

    public enum Tier {
        HOT,
        WARM,
        COLD
    }

    private final Map<String, AtomicLong> accessCounts =
            new ConcurrentHashMap<>();
    private final Map<String, Tier> tiers = new ConcurrentHashMap<>();

    /** 记录访问：热度递增。 */
    public void recordAccess(String viewId) {
        if (viewId == null || viewId.isBlank()) {
            throw new IllegalArgumentException(
                    "viewId required");
        }
        accessCounts.computeIfAbsent(viewId,
                ignored -> new AtomicLong()).incrementAndGet();
    }

    /** 分层决策：>= hotThreshold → HOT；>= warmThreshold → WARM。 */
    public Tier decide(String viewId, long hotThreshold,
                       long warmThreshold) {
        if (viewId == null || viewId.isBlank()) {
            throw new IllegalArgumentException(
                    "viewId required");
        }
        if (hotThreshold < warmThreshold || warmThreshold < 0) {
            throw new IllegalArgumentException(
                    "invalid thresholds");
        }
        long count = accessCounts.getOrDefault(viewId,
                new AtomicLong()).get();
        Tier tier = count >= hotThreshold ? Tier.HOT
                : count >= warmThreshold ? Tier.WARM : Tier.COLD;
        tiers.put(viewId, tier);
        return tier;
    }

    public Tier tier(String viewId) {
        Tier tier = tiers.get(viewId);
        return tier == null ? Tier.COLD : tier;
    }

    public long accessCount(String viewId) {
        return accessCounts.getOrDefault(viewId,
                new AtomicLong()).get();
    }

    public Map<String, Tier> tiers() {
        return Map.copyOf(tiers);
    }

    public Set<String> viewIds() {
        return Set.copyOf(accessCounts.keySet());
    }

    public void resetCounts() {
        accessCounts.clear();
    }
}
