package io.tieringkv.cluster.sharding;

import java.util.List;

/** 分片组（ADR-0035）：节点列表 + leader（由元数据维护）。 */
public record ShardGroup(ShardId shardId, List<String> nodes, String leader) {

    public ShardGroup {
        nodes = List.copyOf(nodes);
    }

    public boolean contains(String nodeId) {
        return nodes.contains(nodeId);
    }
}
