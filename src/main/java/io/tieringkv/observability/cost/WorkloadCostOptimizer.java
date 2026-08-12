package io.tieringkv.observability.cost;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workload 感知成本优化（ADR-0160）：负载画像 → 降本建议
 * （缩容/冷层/压缩）+ 收益/风险等级。
 */
public final class WorkloadCostOptimizer {

    /** 负载画像：读/写/存储/值大小。 */
    public record WorkloadProfile(String tenantId, String domainId,
                                  String cloud, long reads,
                                  long writes, long storageGB,
                                  long valueSizeKB) {

        public WorkloadProfile {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException(
                        "tenantId required");
            }
            if (reads < 0 || writes < 0 || storageGB < 0
                    || valueSizeKB < 0) {
                throw new IllegalArgumentException(
                        "workload metrics must be non-negative");
            }
        }
    }

    public enum SuggestionType {
        SCALE_DOWN,
        COLD_TIER,
        COMPRESSION
    }

    public enum Risk {
        LOW,
        MEDIUM,
        HIGH
    }

    /** 降本建议：类型 + 收益估算 + 风险等级。 */
    public record Suggestion(String tenantId, String domainId,
                             SuggestionType type,
                             double estimatedSavings, Risk risk,
                             String reason) {
    }

    private final long lowActivityThreshold;
    private final double coldWriteRatioThreshold;
    private final long largeValueKB;

    public WorkloadCostOptimizer() {
        this(100, 0.8, 64);
    }

    public WorkloadCostOptimizer(long lowActivityThreshold,
                                 double coldWriteRatioThreshold,
                                 long largeValueKB) {
        if (lowActivityThreshold < 0
                || coldWriteRatioThreshold < 0
                || coldWriteRatioThreshold > 1
                || largeValueKB < 0) {
            throw new IllegalArgumentException(
                    "invalid optimizer thresholds");
        }
        this.lowActivityThreshold = lowActivityThreshold;
        this.coldWriteRatioThreshold = coldWriteRatioThreshold;
        this.largeValueKB = largeValueKB;
    }

    /** 单负载画像分析。 */
    public List<Suggestion> analyze(WorkloadProfile profile,
                                    double currentCost) {
        if (profile == null) {
            throw new IllegalArgumentException(
                    "profile required");
        }
        if (currentCost < 0) {
            throw new IllegalArgumentException(
                    "cost must be non-negative");
        }
        List<Suggestion> suggestions = new ArrayList<>();
        long ops = profile.reads() + profile.writes();
        if (ops < lowActivityThreshold
                && profile.storageGB() > 0) {
            suggestions.add(new Suggestion(profile.tenantId(),
                    profile.domainId(), SuggestionType.SCALE_DOWN,
                    currentCost * 0.30,
                    profile.storageGB() >= 100 ? Risk.MEDIUM
                            : Risk.LOW,
                    "low activity with provisioned storage"));
        }
        if (ops > 0
                && (double) profile.writes() / ops
                >= coldWriteRatioThreshold
                && profile.storageGB() >= 50) {
            suggestions.add(new Suggestion(profile.tenantId(),
                    profile.domainId(), SuggestionType.COLD_TIER,
                    currentCost * 0.50, Risk.MEDIUM,
                    "write-heavy dataset suitable for cold tier"));
        }
        if (profile.valueSizeKB() >= largeValueKB) {
            suggestions.add(new Suggestion(profile.tenantId(),
                    profile.domainId(), SuggestionType.COMPRESSION,
                    currentCost * 0.15, Risk.LOW,
                    "large values benefit from compression"));
        }
        return dedupe(suggestions);
    }

    /** 多租户分析：从成本归因取每租户成本。 */
    public List<Suggestion> analyzeAll(
            Map<String, WorkloadProfile> profiles,
            CostAttribution costs) {
        if (profiles == null || costs == null) {
            throw new IllegalArgumentException(
                    "profiles and costs required");
        }
        List<Suggestion> all = new ArrayList<>();
        for (WorkloadProfile profile : profiles.values()) {
            double cost = costs.byTenant().getOrDefault(
                    profile.tenantId(), 0.0);
            all.addAll(analyze(profile, cost));
        }
        return all;
    }

    private static List<Suggestion> dedupe(
            List<Suggestion> suggestions) {
        Set<String> seen = new HashSet<>();
        List<Suggestion> result = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            String key = suggestion.tenantId() + ":"
                    + suggestion.domainId() + ":" + suggestion.type();
            if (seen.add(key)) {
                result.add(suggestion);
            }
        }
        return result;
    }
}
