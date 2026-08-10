package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardGroup;
import io.tieringkv.cluster.sharding.ShardId;

import java.util.List;

/**
 * 元数据服务（ADR-0036，进程内原型）：JOIN / 拓扑查询 / LEADER-CHANGE。
 */
public final class MetadataServer {

    private final NodeRegistry nodeRegistry = new NodeRegistry();
    private final TopologyManager topologyManager = new TopologyManager();

    /** 注册节点并（首次）为其分配分片。 */
    public synchronized boolean join(String nodeId, int shardCount) {
        boolean added = nodeRegistry.register(nodeId);
        if (topologyManager.shardRegistry().size() == 0 && shardCount > 0) {
            topologyManager.slotTable().assignShards(shardCount);
        }
        return added;
    }

    public synchronized boolean leave(String nodeId) {
        boolean removed = nodeRegistry.unregister(nodeId);
        for (ShardGroup group : topologyManager.shardRegistry().all()) {
            if (group.contains(nodeId)) {
                List<String> remaining = group.nodes().stream()
                        .filter(n -> !n.equals(nodeId))
                        .toList();
                String leader = group.leader().equals(nodeId) ? null : group.leader();
                topologyManager.shardRegistry().put(
                        new ShardGroup(group.shardId(), remaining, leader));
            }
        }
        return removed;
    }

    public synchronized void createShard(ShardId shardId, List<String> nodes, String leader) {
        topologyManager.shardRegistry().put(new ShardGroup(shardId, nodes, leader));
    }

    public synchronized void updateLeader(int shardId, String leader) {
        topologyManager.shardRegistry().updateLeader(shardId, leader);
    }

    public ClusterMetadata metadata() {
        return new ClusterMetadata(nodeRegistry.nodes().stream().toList(),
                topologyManager.shardRegistry().all());
    }

    public TopologyManager topology() {
        return topologyManager;
    }

    public NodeRegistry nodes() {
        return nodeRegistry;
    }
}
