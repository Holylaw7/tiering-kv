package io.tieringkv.sharding.auto;

/** 自动重分片策略（ADR-0132）：阈值 + 冷却 + 熔断。 */
public record ReshardPolicy(long splitQpsThreshold,
                            long mergeQpsThreshold,
                            long cooldownMillis,
                            int maxFailures) {

    public ReshardPolicy {
        if (maxFailures < 1) {
            throw new IllegalArgumentException(
                    "maxFailures >= 1");
        }
    }
}
