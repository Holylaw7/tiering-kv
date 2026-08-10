package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.TopologyManager;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.sharding.ShardGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * INFO CLUSTER 输出（ADR-0056）：节点/角色/term/leader/slot +
 * 集群指标。
 */
public final class ClusterInfo {

    private final String nodeId;
    private final Supplier<RaftNode> raftSupplier;
    private final Supplier<TopologyManager> topologySupplier;
    private final ClusterMetricsRegistry metrics;

    public ClusterInfo(String nodeId,
                       Supplier<RaftNode> raftSupplier,
                       Supplier<TopologyManager> topologySupplier,
                       ClusterMetricsRegistry metrics) {
        this.nodeId = nodeId;
        this.raftSupplier = raftSupplier;
        this.topologySupplier = topologySupplier;
        this.metrics = metrics;
    }

    public String sectionText() {
        RaftNode raft = raftSupplier.get();
        RaftState role = raft == null ? RaftState.FOLLOWER : raft.state();
        long term = raft == null ? 0 : raft.currentTerm();
        String leader = raft == null || raft.leaderId() == null
                ? "" : raft.leaderId();
        StringBuilder builder = new StringBuilder("# Cluster\r\n");
        builder.append("node:").append(nodeId).append("\r\n");
        builder.append("role:").append(role.name().toLowerCase()).append("\r\n");
        builder.append("term:").append(term).append("\r\n");
        builder.append("leader:").append(leader).append("\r\n");
        appendSlots(builder, topologySupplier.get());
        builder.append(metrics.metricLines());
        return builder.toString();
    }

    private static void appendSlots(StringBuilder builder, TopologyManager topology) {
        if (topology == null || topology.shardRegistry().all().isEmpty()) {
            builder.append("slot:unassigned\r\n");
            return;
        }
        for (ShardGroup group : topology.shardRegistry().all()) {
            List<Integer> slots = topology.slotTable()
                    .slotsOf(group.shardId().id());
            List<String> ranges = compressRanges(slots);
            builder.append("slot:").append(group.shardId().id())
                    .append(':').append(String.join(",", ranges))
                    .append("\r\n");
        }
    }

    /** 连续 slot 压缩为区间：1,2,3,7 → 1-3,7。 */
    private static List<String> compressRanges(List<Integer> slots) {
        List<String> ranges = new ArrayList<>();
        if (slots.isEmpty()) {
            return ranges;
        }
        int start = slots.get(0);
        int previous = start;
        for (int i = 1; i < slots.size(); i++) {
            int current = slots.get(i);
            if (current != previous + 1) {
                ranges.add(range(start, previous));
                start = current;
            }
            previous = current;
        }
        ranges.add(range(start, previous));
        return ranges;
    }

    private static String range(int start, int end) {
        return start == end ? String.valueOf(start) : start + "-" + end;
    }
}
