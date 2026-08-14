package io.tieringkv.operator;

import java.util.List;

/**
 * 多集群期望拓扑（ADR-0322 M4）：集群集合 + 跨集群复制边。
 * 每条边表示 from 集群的 regionId 复制到 to 集群（M3 通道接线目标）。
 */
public record MultiClusterTopology(List<String> clusters,
                                   List<ReplicationEdge> edges) {

    public record ReplicationEdge(String from, String to,
                                  String regionId) {
        public ReplicationEdge {
            if (from == null || from.isBlank()
                    || to == null || to.isBlank()
                    || regionId == null || regionId.isBlank()) {
                throw new IllegalArgumentException(
                        "from/to/regionId required");
            }
        }
    }

    public MultiClusterTopology {
        clusters = List.copyOf(clusters);
        edges = List.copyOf(edges);
        if (clusters.isEmpty()) {
            throw new IllegalArgumentException(
                    "clusters required");
        }
        for (String cluster : clusters) {
            if (cluster == null || cluster.isBlank()) {
                throw new IllegalArgumentException(
                        "cluster name required");
            }
        }
        for (ReplicationEdge edge : edges) {
            if (!clusters.contains(edge.from())
                    || !clusters.contains(edge.to())) {
                throw new IllegalArgumentException(
                        "edge endpoint outside clusters: "
                                + edge.from() + "->" + edge.to());
            }
            if (edge.from().equals(edge.to())) {
                throw new IllegalArgumentException(
                        "self replication edge: " + edge.from());
            }
        }
    }
}
