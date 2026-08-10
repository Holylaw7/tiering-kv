package io.tieringkv.cluster.sharding;

/** 分片标识（ADR-0035）。 */
public record ShardId(int id) {

    public ShardId {
        if (id < 0) {
            throw new IllegalArgumentException("shard id must be non-negative");
        }
    }
}
