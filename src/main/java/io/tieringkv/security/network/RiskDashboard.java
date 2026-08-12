package io.tieringkv.security.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 风险视图（ADR-0184）：按租户聚合暴露与评分。 */
public final class RiskDashboard {

    public Map<String, Long> exposureByTenant(
            IsolationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "policy required");
        }
        Map<String, Long> exposure = new ConcurrentHashMap<>();
        for (String pair : policy.whitelistEntries()) {
            String[] tenants = pair.split(":", 2);
            exposure.merge(tenants[0], 1L, Long::sum);
            exposure.merge(tenants[1], 1L, Long::sum);
        }
        return Map.copyOf(exposure);
    }

    public Map<String, Integer> scoreByTenant(
            IsolationPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException(
                    "policy required");
        }
        Map<String, Integer> scores = new ConcurrentHashMap<>();
        Map<String, Long> exposure = exposureByTenant(policy);
        for (String tenantId : policy.tenantIds()) {
            long pairs = exposure.getOrDefault(tenantId, 0L);
            boolean privateExposed = policy.isPrivate(tenantId)
                    && pairs > 0;
            int score = Math.min(100,
                    (int) (pairs * 10)
                            + (privateExposed ? 20 : 0));
            scores.put(tenantId, score);
        }
        return Map.copyOf(scores);
    }
}
