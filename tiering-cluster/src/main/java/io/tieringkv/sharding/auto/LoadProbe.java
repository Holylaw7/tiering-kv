package io.tieringkv.sharding.auto;

/** 负载采样（ADR-0132）：QPS / 延迟 / 分片大小。 */
public record LoadProbe(long qps, long latencyMillis, long shardSize) {
}
