package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardGroup;

import java.util.List;

/** 集群元数据快照（ADR-0036）。 */
public record ClusterMetadata(List<String> nodes, List<ShardGroup> shards) {

    public ClusterMetadata {
        nodes = List.copyOf(nodes);
        shards = List.copyOf(shards);
    }
}
