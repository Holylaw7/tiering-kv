package io.tieringkv.cluster.metrics;

import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 18 可观测性：MetricsExporter / INFO CLUSTER 聚合。 */
class Phase18MetricsTest {

    @Test
    void exporterEmitsHelpAndType() {
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), new GatewayMetricsRegistry());
        assertThat(text).contains("# HELP tiering_region_count")
                .contains("# TYPE tiering_region_count counter");
    }

    @Test
    void exporterEmitsRegionMetrics() {
        RegionMetricsRegistry regions = new RegionMetricsRegistry();
        regions.recordSplit();
        regions.recordMerge();
        regions.setRegionCount(3);
        String text = MetricsExporter.export(regions,
                new RaftMetricsRegistry(), new MigrationMetricsRegistry(),
                new GatewayMetricsRegistry());
        assertThat(text).contains("tiering_region_count 3")
                .contains("tiering_region_split_total 1")
                .contains("tiering_region_merge_total 1");
    }

    @Test
    void exporterEmitsRaftMetrics() {
        RaftMetricsRegistry raft = new RaftMetricsRegistry();
        raft.recordLeaderTransfer();
        raft.recordElection();
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), raft,
                new MigrationMetricsRegistry(), new GatewayMetricsRegistry());
        assertThat(text).contains("tiering_raft_leader_transfer_total 1")
                .contains("tiering_raft_election_total 1")
                .contains("tiering_raft_proposal_latency_ms");
    }

    @Test
    void exporterEmitsMigrationMetrics() {
        MigrationMetricsRegistry migration = new MigrationMetricsRegistry();
        migration.recordBytes(2048);
        migration.recordError();
        migration.setRemaining(9);
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                migration, new GatewayMetricsRegistry());
        assertThat(text).contains("tiering_migration_bytes_total 2048")
                .contains("tiering_migration_remaining 9")
                .contains("tiering_migration_error_total 1");
    }

    @Test
    void exporterEmitsGatewayMetrics() {
        GatewayMetricsRegistry gateway = new GatewayMetricsRegistry();
        gateway.connectionOpened();
        gateway.recordRequest(1_000_000);
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), gateway);
        assertThat(text).contains("tiering_gateway_connections 1")
                .contains("tiering_gateway_qps")
                .contains("tiering_gateway_avg_latency_ms");
    }

    @Test
    void productionInfoAggregatesAllSections() {
        RegionMetricsRegistry regions = new RegionMetricsRegistry();
        regions.setRegionCount(2);
        RaftMetricsRegistry raft = new RaftMetricsRegistry();
        raft.recordLeaderTransfer();
        MigrationMetricsRegistry migration = new MigrationMetricsRegistry();
        migration.recordBytes(1024);
        GatewayMetricsRegistry gateway = new GatewayMetricsRegistry();
        ProductionInfo info = new ProductionInfo(regions, raft, migration, gateway);
        String text = info.clusterSection();
        assertThat(text).startsWith("# Cluster")
                .contains("region_count:2")
                .contains("leader_transfer_total:1")
                .contains("migration_bytes:1024")
                .contains("gateway_connections:0");
    }

    @Test
    void raftMetricLinesWithoutHeader() {
        RaftMetricsRegistry raft = new RaftMetricsRegistry();
        assertThat(raft.metricLines()).doesNotStartWith("#");
    }

    @Test
    void migrationMetricLinesWithoutHeader() {
        MigrationMetricsRegistry migration = new MigrationMetricsRegistry();
        assertThat(migration.metricLines()).doesNotStartWith("#");
    }

    @Test
    void gatewaySectionTextHasHeader() {
        GatewayMetricsRegistry gateway = new GatewayMetricsRegistry();
        assertThat(gateway.sectionText()).startsWith("# Gateway");
    }

    @Test
    void exporterEmptyRegistriesValid() {
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), new GatewayMetricsRegistry());
        assertThat(text).contains("tiering_region_count 0")
                .contains("tiering_gateway_qps");
    }

    @Test
    void productionInfoGatewaySection() {
        GatewayMetricsRegistry gateway = new GatewayMetricsRegistry();
        gateway.connectionOpened();
        gateway.recordRequest(500_000);
        ProductionInfo info = new ProductionInfo(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), gateway);
        assertThat(info.clusterSection()).contains(
                "gateway_connections:1",
                "gateway_qps:",
                "gateway_avg_latency_ms:");
    }
}
