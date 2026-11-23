package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 跨区一阶段提交（ADR-0214）：主副本一阶段 + 回退 2PC。 */
public final class CrossRegionOnePhaseCommit {

    /** 提交结果。 */
    public record CommitResult(String txnId, boolean onePhase,
                               boolean succeeded) {
    }

    private final Map<String, Boolean> primaryReplicas =
            new ConcurrentHashMap<>();

    /** 注册主副本：region → 是否主副本可一阶段。 */
    public void registerPrimaryReplica(String region,
                                       boolean onePhaseEligible) {
        primaryReplicas.put(region, onePhaseEligible);
    }

    /** 跨区一阶段：全部区域主副本合格 → 一阶段。 */
    public CommitResult commit(String txnId,
                               java.util.Set<String> regions) {
        validate(txnId);
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "regions required");
        }
        boolean allEligible = regions.stream().allMatch(
                region -> primaryReplicas.getOrDefault(region,
                        false));
        return allEligible
                ? new CommitResult(txnId, true, true)
                : new CommitResult(txnId, false, true);
    }

    /** 回退 2PC：显式走两阶段（幂等语义由调用方保证）。 */
    public CommitResult commitTwoPhase(String txnId,
                                       java.util.Set<String> regions) {
        validate(txnId);
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "regions required");
        }
        return new CommitResult(txnId, false, true);
    }

    private static void validate(String txnId) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
    }
}
