package io.tieringkv.cluster.region;

/** Region 标识（ADR-0057）：替代 ShardId 的路由单元。 */
public record RegionId(int id) {

    public RegionId {
        if (id < 0) {
            throw new IllegalArgumentException("region id must be non-negative");
        }
    }
}
