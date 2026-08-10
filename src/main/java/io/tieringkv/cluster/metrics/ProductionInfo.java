package io.tieringkv.cluster.metrics;

import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;

/** INFO CLUSTER 聚合（ADR-0070）：Region/Raft/Migration/Gateway 全量。 */
public final class ProductionInfo {

    private final RegionMetricsRegistry regions;
    private final RaftMetricsRegistry raft;
    private final MigrationMetricsRegistry migration;
    private final GatewayMetricsRegistry gateway;

    public ProductionInfo(RegionMetricsRegistry regions,
                          RaftMetricsRegistry raft,
                          MigrationMetricsRegistry migration,
                          GatewayMetricsRegistry gateway) {
        this.regions = regions;
        this.raft = raft;
        this.migration = migration;
        this.gateway = gateway;
    }

    public String clusterSection() {
        return "# Cluster\r\n"
                + regions.metricLines()
                + raft.metricLines()
                + migration.metricLines()
                + gateway.metricLines();
    }
}
