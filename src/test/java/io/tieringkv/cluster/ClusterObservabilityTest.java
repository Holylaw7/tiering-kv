package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.TopologyManager;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.sharding.ShardGroup;
import io.tieringkv.cluster.sharding.ShardId;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

/** 集群可观测性（ADR-0056）：指标注册表 + INFO CLUSTER。 */
class ClusterObservabilityTest {

    @Test
    void proposalQpsReflectsRecordedProposals() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        for (int i = 0; i < 100; i++) {
            metrics.recordProposal();
        }
        assertThat(metrics.snapshot().raftProposalQps()).isGreaterThan(0);
    }

    @Test
    void commitLatencyIsAveraged() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.recordCommitLatency(1_000_000); // 1ms
        metrics.recordCommitLatency(3_000_000); // 3ms
        assertThat(metrics.snapshot().raftCommitLatencyMs()).isEqualTo(2.0);
    }

    @Test
    void replicationLagTracksLatestValue() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.setReplicationLag(5);
        metrics.setReplicationLag(42);
        assertThat(metrics.snapshot().raftReplicationLag()).isEqualTo(42);
    }

    @Test
    void migrationRateReflectsRecordedBytes() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.recordMigrationBytes(1_000_000);
        assertThat(metrics.snapshot().migrationSpeedBytesPerSec()).isGreaterThan(0);
    }

    @Test
    void migrationCursorAndRemainingTracked() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.setMigrationCursor(123_456);
        metrics.setMigrationRemaining(77);
        ClusterMetricsRegistry.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.migrationCursor()).isEqualTo(123_456);
        assertThat(snapshot.migrationRemaining()).isEqualTo(77);
    }

    @Test
    void certificateExpireTimeTracked() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.setCertificateExpireMillis(3_600_000);
        assertThat(metrics.snapshot().certificateExpireMillis()).isEqualTo(3_600_000);
    }

    @Test
    void negativeInputsClampedToZero() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.setReplicationLag(-1);
        metrics.setMigrationCursor(-5);
        metrics.setMigrationRemaining(-9);
        metrics.setCertificateExpireMillis(-100);
        ClusterMetricsRegistry.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.raftReplicationLag()).isZero();
        assertThat(snapshot.migrationCursor()).isZero();
        assertThat(snapshot.migrationRemaining()).isZero();
        assertThat(snapshot.certificateExpireMillis()).isZero();
    }

    @Test
    void sectionTextContainsAllMetricNames() {
        ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
        metrics.recordProposal();
        metrics.recordCommitLatency(500_000);
        metrics.setReplicationLag(3);
        metrics.recordMigrationBytes(2048);
        metrics.setMigrationCursor(10);
        metrics.setMigrationRemaining(20);
        metrics.setCertificateExpireMillis(3600_000);
        String text = metrics.sectionText();
        assertThat(text).startsWith("# Cluster");
        assertThat(text).contains(
                "raft_proposal_qps:",
                "raft_commit_latency_ms:",
                "raft_replication_lag:3",
                "migration_speed_bytes_per_sec:",
                "migration_cursor:10",
                "migration_remaining:20",
                "certificate_expire_time_ms:3600000");
    }

    @Test
    void clusterInfoContainsNodeRoleTermLeaderAndSlots() throws Exception {
        List<String> applied = new ArrayList<>();
        RaftNode raft = RaftTestSupport.node("n1", List.of(), applied);
        raft.start();
        try {
            awaitTrue("solo leader", () -> raft.state() == io.tieringkv.cluster.raft.RaftState.LEADER, 3000);
            TopologyManager topology = new TopologyManager();
            topology.slotTable().assignShards(1);
            topology.shardRegistry().put(new ShardGroup(
                    new ShardId(0), List.of("n1"), "n1"));
            ClusterMetricsRegistry metrics = new ClusterMetricsRegistry();
            metrics.setReplicationLag(7);
            ClusterInfo info = new ClusterInfo("n1", () -> raft, () -> topology, metrics);
            String text = info.sectionText();
            assertThat(text).contains(
                    "# Cluster",
                    "node:n1",
                    "role:leader",
                    "term:",
                    "leader:n1",
                    "slot:0:0-16383",
                    "raft_replication_lag:7");
        } finally {
            raft.close();
        }
    }

    @Test
    void clusterInfoUnassignedSlotsReported() {
        ClusterInfo info = new ClusterInfo("n1",
                () -> null, () -> new TopologyManager(),
                new ClusterMetricsRegistry());
        assertThat(info.sectionText()).contains("slot:unassigned");
    }

    @Test
    void clusterInfoSlotRangesCompressedAcrossShards() {
        TopologyManager topology = new TopologyManager();
        topology.slotTable().assignShards(2);
        topology.shardRegistry().put(new ShardGroup(
                new ShardId(0), List.of("n1"), "n1"));
        topology.shardRegistry().put(new ShardGroup(
                new ShardId(1), List.of("n1"), "n1"));
        ClusterInfo info = new ClusterInfo("n1",
                () -> null, () -> topology, new ClusterMetricsRegistry());
        String text = info.sectionText();
        assertThat(text).contains("slot:0:", "slot:1:");
        assertThat(text).doesNotContain("slot:unassigned");
    }

    @Test
    void infoWithoutArgsReturnsFullText() {
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\nfull\r\n", Map.of());
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand("info", List.of()));
        assertThat(response).isInstanceOf(RespBulkString.class);
        assertThat(text(response)).startsWith("# Server");
    }

    @Test
    void infoClusterReturnsClusterSection() {
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\nfull\r\n",
                Map.of("cluster", () -> "# Cluster\r\nnode:n1\r\n"));
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand(
                "info", List.of("cluster".getBytes(StandardCharsets.UTF_8))));
        assertThat(response).isInstanceOf(RespBulkString.class);
        assertThat(text(response)).startsWith("# Cluster").contains("node:n1");
    }

    @Test
    void infoUnknownSectionReturnsError() {
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\n", Map.of("cluster", () -> "# Cluster\r\n"));
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand(
                "info", List.of("bogus".getBytes(StandardCharsets.UTF_8))));
        assertThat(response).isInstanceOf(RespError.class);
    }

    @Test
    void infoExtraArgsReturnsWrongArity() {
        CommandRegistry registry = CommandRegistry.createDefault();
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand("info",
                List.of("cluster".getBytes(StandardCharsets.UTF_8),
                        "extra".getBytes(StandardCharsets.UTF_8))));
        assertThat(response).isInstanceOf(RespError.class);
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(), StandardCharsets.UTF_8);
    }
}
