package io.tieringkv.cluster.topology;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 拓扑自发现（ADR-0211）：心跳 → 地域/可用区分组。 */
public final class TopologyDiscovery {

    /** 节点心跳。 */
    public record Heartbeat(String nodeId, String region,
                            String availabilityZone,
                            long timestampMillis) {
    }

    /** 节点状态。 */
    public record NodeInfo(String nodeId, String region,
                           String availabilityZone,
                           boolean healthy) {
    }

    private final Map<String, NodeInfo> nodes =
            new ConcurrentHashMap<>();
    private final long healthTimeoutMillis;

    public TopologyDiscovery(long healthTimeoutMillis) {
        if (healthTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "health timeout must be positive");
        }
        this.healthTimeoutMillis = healthTimeoutMillis;
    }

    public void heartbeat(Heartbeat heartbeat, long nowMillis) {
        if (heartbeat == null || heartbeat.nodeId() == null
                || heartbeat.nodeId().isBlank()) {
            throw new IllegalArgumentException(
                    "heartbeat required");
        }
        boolean healthy = nowMillis - heartbeat.timestampMillis()
                <= healthTimeoutMillis;
        nodes.put(heartbeat.nodeId(), new NodeInfo(
                heartbeat.nodeId(), heartbeat.region(),
                heartbeat.availabilityZone(), healthy));
    }

    public List<NodeInfo> nodes() {
        return List.copyOf(nodes.values());
    }

    public Map<String, Set<String>> groupByRegion() {
        Map<String, Set<String>> groups = new ConcurrentHashMap<>();
        nodes.values().forEach(node -> groups.computeIfAbsent(
                node.region(), ignored ->
                        ConcurrentHashMap.newKeySet())
                .add(node.nodeId()));
        return groups;
    }

    public Map<String, Set<String>> groupByAz() {
        Map<String, Set<String>> groups = new ConcurrentHashMap<>();
        nodes.values().forEach(node -> groups.computeIfAbsent(
                node.availabilityZone(), ignored ->
                        ConcurrentHashMap.newKeySet())
                .add(node.nodeId()));
        return groups;
    }

    public void remove(String nodeId) {
        nodes.remove(nodeId);
    }

    public int size() {
        return nodes.size();
    }
}
