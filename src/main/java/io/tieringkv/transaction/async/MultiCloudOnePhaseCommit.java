package io.tieringkv.transaction.async;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨云全局一阶段提交（ADR-0228）：多云主副本资格 → 多数云仲裁 →
 * 跨云一阶段；少数云不合格回退 2PC。幂等由 txnId + 云集合去重保证。
 */
public final class MultiCloudOnePhaseCommit {

    /** 跨云提交结果。 */
    public record CloudCommitResult(String txnId, boolean onePhase,
                                    boolean succeeded,
                                    int clouds, int eligibleClouds) {
    }

    private final Map<String, Boolean> cloudEligibility =
            new ConcurrentHashMap<>();
    private final Map<String, CloudCommitResult> completed =
            new ConcurrentHashMap<>();
    private volatile ResolvedTimestampService resolvedTs;

    /** 注册云主副本资格：cloud → 是否跨云一阶段合格。 */
    public void registerCloud(String cloud,
                              boolean onePhaseEligible) {
        if (cloud == null || cloud.isBlank()) {
            throw new IllegalArgumentException(
                    "cloud required");
        }
        cloudEligibility.put(cloud, onePhaseEligible);
        // 资格变化使旧判定失效：幂等缓存重算
        completed.clear();
    }

    /** 标记云不可用：降级为 2PC 参与方。 */
    public void markUnavailable(String cloud) {
        registerCloud(cloud, false);
    }

    /** 关联 resolved-ts：跨云一阶段成功后推进水位。 */
    public void attachResolvedTimestamp(
            ResolvedTimestampService service) {
        if (service == null) {
            throw new IllegalArgumentException(
                    "service required");
        }
        this.resolvedTs = service;
    }

    /** 跨云一阶段：合格云数 > 云数/2 → 一阶段；否则回退 2PC。 */
    public CloudCommitResult commit(String txnId,
                                    Set<String> clouds) {
        return commit(txnId, clouds, Long.MIN_VALUE);
    }

    /** 携带全局时间戳提交：一阶段成功后推进 resolved 水位。 */
    public CloudCommitResult commit(String txnId,
                                    Set<String> clouds,
                                    long commitTs) {
        validate(txnId, clouds);
        String cacheKey = cacheKey(txnId, clouds);
        CloudCommitResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long eligible = clouds.stream()
                .filter(cloud -> cloudEligibility.getOrDefault(
                        cloud, false))
                .count();
        boolean quorum = eligible > clouds.size() / 2;
        CloudCommitResult result = new CloudCommitResult(
                txnId, quorum, true, clouds.size(),
                (int) eligible);
        completed.putIfAbsent(cacheKey, result);
        if (quorum && commitTs != Long.MIN_VALUE
                && resolvedTs != null) {
            resolvedTs.advance(commitTs);
        }
        return completed.get(cacheKey);
    }

    /** 显式 2PC 回退：始终两阶段，成功语义保持。 */
    public CloudCommitResult commitTwoPhase(
            String txnId, Set<String> clouds) {
        validate(txnId, clouds);
        String cacheKey = cacheKey(txnId, clouds);
        CloudCommitResult cached = completed.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        long eligible = clouds.stream()
                .filter(cloud -> cloudEligibility.getOrDefault(
                        cloud, false))
                .count();
        CloudCommitResult result = new CloudCommitResult(
                txnId, false, true, clouds.size(),
                (int) eligible);
        completed.putIfAbsent(cacheKey, result);
        return completed.get(cacheKey);
    }

    private static void validate(String txnId,
                                 Set<String> clouds) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
        if (clouds == null || clouds.isEmpty()) {
            throw new IllegalArgumentException(
                    "clouds required");
        }
    }

    /** 幂等缓存键：txnId + 排序后的云集合（避免跨云复用）。 */
    private static String cacheKey(String txnId,
                                   Set<String> clouds) {
        return txnId + "|" + clouds.stream().sorted()
                .reduce((a, b) -> a + "," + b).orElse("");
    }
}
