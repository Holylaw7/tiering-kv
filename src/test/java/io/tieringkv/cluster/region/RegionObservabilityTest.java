package io.tieringkv.cluster.region;

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

/** Region 可观测性（ADR-0056/0060）：指标 + INFO REGIONS。 */
class RegionObservabilityTest {

    @Test
    void splitCountTracked() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.recordSplit();
        metrics.recordSplit();
        assertThat(metrics.snapshot().regionSplitCount()).isEqualTo(2);
    }

    @Test
    void moveBytesTracked() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.recordRegionMoveBytes(1_024);
        metrics.recordRegionMoveBytes(512);
        assertThat(metrics.snapshot().regionMoveBytes()).isEqualTo(1_536);
    }

    @Test
    void negativeMoveBytesClamped() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.recordRegionMoveBytes(-10);
        assertThat(metrics.snapshot().regionMoveBytes()).isZero();
    }

    @Test
    void countersSetFromRuntime() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.setRegionCount(4);
        metrics.setRegionSize(2_000);
        metrics.setRaftGroupCount(4);
        metrics.setLeaderDistribution("n1:2,n2:1,n3:1");
        RegionMetricsRegistry.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.regionCount()).isEqualTo(4);
        assertThat(snapshot.regionSizeBytes()).isEqualTo(2_000);
        assertThat(snapshot.raftGroupCount()).isEqualTo(4);
        assertThat(snapshot.leaderDistribution()).isEqualTo("n1:2,n2:1,n3:1");
    }

    @Test
    void metricLinesContainAllNames() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.recordSplit();
        metrics.recordRegionMoveBytes(42);
        metrics.setRegionCount(2);
        metrics.setRaftGroupCount(2);
        String text = metrics.sectionText();
        assertThat(text).startsWith("# Regions");
        assertThat(text).contains(
                "region_count:2",
                "region_size_bytes:0",
                "region_split_count:1",
                "raft_group_count:2",
                "leader_distribution:",
                "region_move_bytes:42");
    }

    @Test
    void regionInfoContainsRegionLines() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("m"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        regions.setRegionSize(new RegionId(1), 777);
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.setRegionCount(1);
        RegionInfo info = new RegionInfo(regions, metrics);
        String text = info.sectionText();
        assertThat(text).contains(
                "# Regions",
                "region:1:n1:1.1:777:NORMAL",
                "region_count:1");
    }

    @Test
    void regionInfoEmptyManager() {
        RegionInfo info = new RegionInfo(new RegionManager(),
                new RegionMetricsRegistry());
        assertThat(info.sectionText()).startsWith("# Regions");
        assertThat(info.sectionText()).contains("region_count:0");
    }

    @Test
    void infoRegionsCommandReturnsSection() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        RegionInfo info = new RegionInfo(regions, metrics);
        CommandRegistry registry = CommandRegistry.createDefault(
                () -> "# Server\r\n",
                Map.of("regions", info::sectionText));
        CommandEngine engine = new CommandEngine(registry, MemTable.create());
        RespValue response = engine.execute(new RespCommand("info",
                List.of("regions".getBytes(StandardCharsets.UTF_8))));
        assertThat(response).isInstanceOf(RespBulkString.class);
        String text = new String(((RespBulkString) response).bytes(),
                StandardCharsets.UTF_8);
        assertThat(text).startsWith("# Regions")
                .contains("region:1:n1:1.1:0:NORMAL");
    }

    @Test
    void splitIncrementsMetricsViaManagerState() {
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.setRegionCount(regions.regionCount());
        regions.splitRegion(new RegionId(1), bytes("m"));
        metrics.recordSplit();
        metrics.setRegionCount(regions.regionCount());
        assertThat(metrics.snapshot().regionCount()).isEqualTo(2);
        assertThat(metrics.snapshot().regionSplitCount()).isEqualTo(1);
    }

    @Test
    void leaderDistributionFromPlacement() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.setLeaderDistribution("n1:2,n2:1,n3:1");
        assertThat(metrics.snapshot().leaderDistribution())
                .contains("n1:2");
    }

    @Test
    void nullDistributionTreatedAsEmpty() {
        RegionMetricsRegistry metrics = new RegionMetricsRegistry();
        metrics.setLeaderDistribution(null);
        assertThat(metrics.snapshot().leaderDistribution()).isEmpty();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
