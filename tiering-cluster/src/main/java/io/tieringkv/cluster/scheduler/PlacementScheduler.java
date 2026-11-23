package io.tieringkv.cluster.scheduler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 放置调度（ADR-0205）：机架/可用区约束 + epoch 保护。 */
public final class PlacementScheduler {

    /** 节点：ID + 可用区。 */
    public record Node(String nodeId, String availabilityZone) {
    }

    private final Map<String, Node> nodes = new ConcurrentHashMap<>();
    private final Map<String, String> placements =
            new ConcurrentHashMap<>();
    private volatile long epoch;

    public void registerNode(Node node) {
        if (node == null || node.nodeId() == null
                || node.nodeId().isBlank()) {
            throw new IllegalArgumentException(
                    "node required");
        }
        nodes.put(node.nodeId(), node);
    }

    /** 放置到指定可用区节点（约束校验）。 */
    public String place(String regionId, String availabilityZone,
                        long expectedEpoch) {
        if (regionId == null || regionId.isBlank()
                || availabilityZone == null
                || availabilityZone.isBlank()) {
            throw new IllegalArgumentException(
                    "region and az required");
        }
        if (expectedEpoch != epoch) {
            throw new IllegalStateException(
                    "epoch mismatch");
        }
        String nodeId = nodes.values().stream()
                .filter(node -> node.availabilityZone()
                        .equals(availabilityZone))
                .map(Node::nodeId).findFirst().orElse(null);
        if (nodeId == null) {
            throw new IllegalStateException(
                    "no node in availability zone "
                            + availabilityZone);
        }
        placements.put(regionId, nodeId);
        return nodeId;
    }

    public String placement(String regionId) {
        return placements.get(regionId);
    }

    public boolean canPlace(String nodeId,
                            String availabilityZone) {
        Node node = nodes.get(nodeId);
        return node != null
                && node.availabilityZone()
                .equals(availabilityZone);
    }

    public synchronized void advanceEpoch() {
        epoch++;
    }

    public long epoch() {
        return epoch;
    }

    public Set<String> regions() {
        return Set.copyOf(placements.keySet());
    }

    public int nodeCount() {
        return nodes.size();
    }
}
