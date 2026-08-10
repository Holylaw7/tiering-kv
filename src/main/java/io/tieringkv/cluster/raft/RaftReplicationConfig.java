package io.tieringkv.cluster.raft;

/** 批量复制配置（ADR-0044）：batch 大小/字节/刷新间隔/最大 in-flight。 */
public record RaftReplicationConfig(
        int maxBatchEntries,
        int maxBatchBytes,
        long flushIntervalMillis,
        int maxInflight) {

    public RaftReplicationConfig {
        if (maxBatchEntries <= 0 || maxBatchBytes <= 0
                || flushIntervalMillis <= 0 || maxInflight <= 0) {
            throw new IllegalArgumentException("invalid replication config");
        }
    }

    public static RaftReplicationConfig defaults() {
        return new RaftReplicationConfig(128, 1 << 20, 5, 8);
    }
}
