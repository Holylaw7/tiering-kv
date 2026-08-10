package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.MetadataServer;

import java.util.Map;

/** 集群客户端（ADR-0035）：按 slot → shard → leader 路由；leader 变化重试一次。 */
public final class ClusterClient {

    private final MetadataServer metadata;
    private final Map<String, ClusterNode> nodes;

    public ClusterClient(MetadataServer metadata, Map<String, ClusterNode> nodes) {
        this.metadata = metadata;
        this.nodes = Map.copyOf(nodes);
    }

    public void put(byte[] key, byte[] value) {
        leaderFor(key).put(key, value);
    }

    public byte[] get(byte[] key) {
        return leaderFor(key).get(key);
    }

    public boolean delete(byte[] key) {
        return leaderFor(key).delete(key);
    }

    private ClusterNode leaderFor(byte[] key) {
        String leaderId = metadata.topology().leaderFor(key);
        ClusterNode node = nodes.get(leaderId);
        if (node != null && node.isLeader()) {
            return node;
        }
        // 元数据可能滞后于选举：重查一次
        leaderId = metadata.topology().leaderFor(key);
        node = nodes.get(leaderId);
        if (node == null) {
            throw new IllegalStateException("no node for leader " + leaderId);
        }
        return node;
    }
}
