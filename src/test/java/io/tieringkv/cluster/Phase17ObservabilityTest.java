package io.tieringkv.cluster;

import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 17 可观测性：INFO RAFT / INFO MIGRATION / merge_count 等。 */
class Phase17ObservabilityTest {

    @Test
    void raftMetricsTracked() {
        RaftMetricsRegistry metrics = new RaftMetricsRegistry();
        metrics.recordLeaderTransfer();
        metrics.recordElection();
        metrics.recordProposalLatency(1_000_000);
        RaftMetricsRegistry.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.leaderTransferTotal()).isEqualTo(1);
        assertThat(snapshot.electionTotal()).isEqualTo(1);
        assertThat(snapshot.proposalLatencyMs()).isEqualTo(1.0);
    }

    @Test
    void raftSectionText() {
        RaftMetricsRegistry metrics = new RaftMetricsRegistry();
        metrics.recordLeaderTransfer();
        String text = metrics.sectionText();
        assertThat(text).startsWith("# Raft")
                .contains("leader_transfer_total:1",
                        "election_total:0",
                        "proposal_latency_ms:");
    }

    @Test
    void migrationMetricsTracked() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordBytes(1_048_576);
        assertThat(metrics.snapshot().migrationBytes()).isEqualTo(1_048_576);
        assertThat(metrics.snapshot().migrationSpeedMbPerSec()).isGreaterThan(0);
    }

    @Test
    void migrationSectionText() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordBytes(1024);
        String text = metrics.sectionText();
        assertThat(text).startsWith("# Migration")
                .contains("migration_bytes:1024",
                        "migration_speed_mb_per_sec:");
    }

    @Test
    void regionMergeCountTracked() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.recordMerge();
        assertThat(metrics.snapshot().regionMergeCount()).isEqualTo(1);
        assertThat(metrics.sectionText()).contains("region_merge_count:1");
    }

    @Test
    void infoRaftCommandReturnsSection() {
        RaftMetricsRegistry metrics = new RaftMetricsRegistry();
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\n",
                Map.of("raft", metrics::sectionText));
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand("info",
                List.of("raft".getBytes(StandardCharsets.UTF_8))));
        assertThat(text(response)).startsWith("# Raft");
    }

    @Test
    void infoMigrationCommandReturnsSection() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\n",
                Map.of("migration", metrics::sectionText));
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand("info",
                List.of("migration".getBytes(StandardCharsets.UTF_8))));
        assertThat(text(response)).startsWith("# Migration");
    }

    @Test
    void allSectionsWiredTogether() {
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\n",
                Map.of(
                        "raft", new RaftMetricsRegistry()::sectionText,
                        "migration", new MigrationMetricsRegistry()::sectionText,
                        "regions", new RegionMetricsRegistry()::sectionText));
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        assertThat(text(engine.execute(new RespCommand("info",
                List.of("regions".getBytes(StandardCharsets.UTF_8))))))
                .startsWith("# Regions");
    }

    @Test
    void proposalLatencyMultipleSamples() {
        RaftMetricsRegistry metrics = new RaftMetricsRegistry();
        metrics.recordProposalLatency(500_000);
        metrics.recordProposalLatency(1_500_000);
        assertThat(metrics.snapshot().proposalLatencyMs()).isEqualTo(1.0);
    }

    @Test
    void negativeMigrationBytesClamped() {
        MigrationMetricsRegistry metrics = new MigrationMetricsRegistry();
        metrics.recordBytes(-10);
        assertThat(metrics.snapshot().migrationBytes()).isZero();
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }
}
