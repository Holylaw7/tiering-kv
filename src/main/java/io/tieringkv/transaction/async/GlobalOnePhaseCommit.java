package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局一阶段提交规模化（ADR-0221）：3 地 / 5 地主副本资格 →
 * 全局一阶段；任一区域不合格回退 2PC。幂等由 txnId 去重保证。
 */
public final class GlobalOnePhaseCommit {

    /** 全局提交结果。 */
    public record GlobalCommitResult(String txnId, boolean onePhase,
                                     boolean succeeded,
                                     int regions) {
    }

    private final Map<String, Boolean> primaryReplicas =
            new ConcurrentHashMap<>();
    private final Map<String, GlobalCommitResult> completed =
            new ConcurrentHashMap<>();
    private volatile ResolvedTimestampService resolvedTs;

    /** 注册主副本：region → 是否全局一阶段合格。 */
    public void registerPrimaryReplica(String region,
                                       boolean onePhaseEligible) {
        if (region == null || region.isBlank()) {
            throw new IllegalArgumentException(
                    "region required");
        }
        primaryReplicas.put(region, onePhaseEligible);
    }

    /** 关联 resolved-ts：全局一阶段成功后推进水位。 */
    public void attachResolvedTimestamp(
            ResolvedTimestampService service) {
        if (service == null) {
            throw new IllegalArgumentException(
                    "service required");
        }
        this.resolvedTs = service;
    }

    /** 全局一阶段：全部区域主副本合格 → 一阶段；否则回退 2PC。 */
    public GlobalCommitResult commit(String txnId,
                                     Set<String> regions) {
        return commit(txnId, regions, Long.MIN_VALUE);
    }

    /** 携带全局时间戳提交：一阶段成功后推进 resolved 水位。 */
    public GlobalCommitResult commit(String txnId,
                                     Set<String> regions,
                                     long commitTs) {
        validate(txnId, regions);
        String cacheKey = cacheKey(txnId, regions);
        GlobalCommitResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        boolean allEligible = regions.stream().allMatch(
                region -> primaryReplicas.getOrDefault(region,
                        false));
        GlobalCommitResult result = new GlobalCommitResult(
                txnId, allEligible, true, regions.size());
        completed.putIfAbsent(cacheKey, result);
        if (allEligible && commitTs != Long.MIN_VALUE
                && resolvedTs != null) {
            resolvedTs.advance(commitTs);
        }
        return completed.get(cacheKey);
    }

    /** 显式 2PC 回退：始终两阶段，成功语义保持。 */
    public GlobalCommitResult commitTwoPhase(
            String txnId, Set<String> regions) {
        validate(txnId, regions);
        String cacheKey = cacheKey(txnId, regions);
        GlobalCommitResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        GlobalCommitResult result = new GlobalCommitResult(
                txnId, false, true, regions.size());
        completed.putIfAbsent(cacheKey, result);
        return completed.get(cacheKey);
    }

    private static void validate(String txnId, Set<String> regions) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "regions required");
        }
    }

    /** 幂等缓存键：txnId + 排序后的区域集合（避免跨区域复用）。 */
    private static String cacheKey(String txnId,
                                   Set<String> regions) {
        return txnId + "|" + regions.stream().sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}
