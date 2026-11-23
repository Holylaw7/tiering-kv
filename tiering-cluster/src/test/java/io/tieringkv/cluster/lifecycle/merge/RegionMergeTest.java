package io.tieringkv.cluster.lifecycle.merge;

import io.tieringkv.cluster.lifecycle.RegionLifecycleState;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Region 合并（ADR-0062）：邻接校验 / 数据搬迁 / 元数据合并 / tombstone。 */
class RegionMergeTest {

    @Test
    void beginMergeValidatesAdjacency() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(task.phase()).isEqualTo(MergeTask.MergePhase.LOCK);
        } finally {
            fixture.close();
        }
    }

    @Test
    void beginMergeRejectsNonAdjacent() {
        Fixture fixture = fixture();
        try {
            fixture.regions.createRegion(new RegionId(9),
                    bytes("n"), bytes("z"),
                    List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
            assertThatThrownBy(() -> fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(9),
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void beginMergeRejectsTombstone() {
        Fixture fixture = fixture();
        try {
            fixture.regions.splitRegion(new RegionId(1), bytes("k0025"));
            assertThatThrownBy(() -> fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void beginMergeRejectsMissingLeader() {
        Fixture fixture = fixture();
        try {
            fixture.regions.createRegion(new RegionId(7),
                    bytes("a"), bytes("m"),
                    List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, null);
            fixture.regions.createRegion(new RegionId(8),
                    bytes("m"), bytes("z"),
                    List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, null);
            assertThatThrownBy(() -> fixture.controller.beginMerge(
                    new RegionId(7), new RegionId(8),
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void lockMarksBothMerging() {
        Fixture fixture = fixture();
        try {
            fixture.controller.beginMerge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(fixture.regions.get(new RegionId(1)).state())
                    .isEqualTo(RegionState.MERGING);
            assertThat(fixture.regions.get(new RegionId(2)).state())
                    .isEqualTo(RegionState.MERGING);
        } finally {
            fixture.close();
        }
    }

    @Test
    void transferMovesRightDataToLeft() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            int moved = fixture.controller.transfer(task);
            assertThat(moved).isEqualTo(50);
            assertThat(fixture.left.size()).isEqualTo(100);
            assertThat(fixture.left.get(bytes("k0050"))).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void transferKeepsLeftData() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            fixture.controller.transfer(task);
            assertThat(fixture.left.get(bytes("k0001"))).isNotNull();
            assertThat(fixture.left.get(bytes("k0049"))).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void transferPreservesValues() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            fixture.controller.transfer(task);
            assertThat(fixture.left.get(bytes("k0099"))).isEqualTo(bytes("v"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void updateMetaCreatesMergedRegion() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            fixture.controller.transfer(task);
            Region merged = fixture.controller.updateMeta(task);
            assertThat(merged.regionId().id()).isEqualTo(13);
            assertThat(fixture.regions.regionCount()).isEqualTo(1);
        } finally {
            fixture.close();
        }
    }

    @Test
    void updateMetaAdvancesEpoch() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            fixture.controller.transfer(task);
            Region merged = fixture.controller.updateMeta(task);
            assertThat(merged.epoch().confVer()).isEqualTo(2);
            assertThat(merged.epoch().version()).isEqualTo(2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void updateMetaTombstonesOldRegions() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            fixture.controller.transfer(task);
            fixture.controller.updateMeta(task);
            assertThat(fixture.regions.get(new RegionId(1)).state())
                    .isEqualTo(RegionState.TOMBSTONE);
            assertThat(fixture.regions.get(new RegionId(2)).state())
                    .isEqualTo(RegionState.TOMBSTONE);
        } finally {
            fixture.close();
        }
    }

    @Test
    void updateMetaWithoutTransferRejected() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThatThrownBy(() -> fixture.controller.updateMeta(task))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergeEndToEnd() {
        Fixture fixture = fixture();
        try {
            Region merged = fixture.controller.merge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(merged.regionId().id()).isEqualTo(13);
            assertThat(fixture.left.size()).isEqualTo(100);
            assertThat(fixture.controller.lifecycleState(new RegionId(1)))
                    .isEqualTo(RegionLifecycleState.TOMBSTONE);
            assertThat(fixture.controller.lifecycleState(new RegionId(13)))
                    .isEqualTo(RegionLifecycleState.NORMAL);
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergedRouteCoversBothRanges() {
        Fixture fixture = fixture();
        try {
            fixture.controller.merge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(fixture.regions.route(bytes("k0001")).regionId().id())
                    .isEqualTo(13);
            assertThat(fixture.regions.route(bytes("k0099")).regionId().id())
                    .isEqualTo(13);
        } finally {
            fixture.close();
        }
    }

    @Test
    void oldRegionsRejectWrites() {
        Fixture fixture = fixture();
        try {
            RegionEpoch oldEpoch = fixture.regions.get(new RegionId(1)).epoch();
            fixture.controller.merge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(fixture.regions.guardEpoch(new RegionId(1), oldEpoch))
                    .isFalse();
        } finally {
            fixture.close();
        }
    }

    @Test
    void concurrentMergeRejected() {
        Fixture fixture = fixture();
        try {
            fixture.controller.beginMerge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThatThrownBy(() -> fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergeEmptyRightRegion() {
        Fixture fixture = fixture();
        try {
            MemTable empty = MemTable.create();
            Region merged = fixture.controller.merge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, empty);
            assertThat(fixture.left.size()).isEqualTo(50);
            assertThat(merged.regionId().id()).isEqualTo(13);
            empty.close();
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergePreservesAllData() {
        Fixture fixture = fixture();
        try {
            fixture.controller.merge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(fixture.left.size()).isEqualTo(100);
            for (int i = 0; i < 100; i++) {
                assertThat(fixture.left.get(bytes("k" + String.format("%04d", i))))
                        .isNotNull();
            }
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergedRegionLeaderFromLeft() {
        Fixture fixture = fixture();
        try {
            Region merged = fixture.controller.merge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(merged.leader()).isEqualTo("n1");
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergeThenSplitWorks() {
        Fixture fixture = fixture();
        try {
            fixture.controller.merge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            List<Region> children = fixture.controller.regions()
                    .splitRegion(new RegionId(13), bytes("k0050"));
            assertThat(children).hasSize(2);
            assertThat(fixture.regions.regionCount()).isEqualTo(2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergePreservesTtl() {
        Fixture fixture = fixture();
        try {
            fixture.right.put(bytes("k0050"), bytes("v"), 10_000);
            fixture.controller.merge(new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(fixture.left.getEntry(bytes("k0050")).expireTimestamp())
                    .isGreaterThan(0);
        } finally {
            fixture.close();
        }
    }

    @Test
    void lifecycleStateDuringMerge() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            assertThat(fixture.controller.lifecycleState(new RegionId(1)))
                    .isEqualTo(RegionLifecycleState.MERGING);
            fixture.controller.transfer(task);
            fixture.controller.updateMeta(task);
            assertThat(fixture.controller.lifecycleState(new RegionId(13)))
                    .isEqualTo(RegionLifecycleState.MERGE_READY);
        } finally {
            fixture.close();
        }
    }

    @Test
    void mergeFailureKeepsStateForRetry() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            // 模拟失败：未 transfer 直接 updateMeta 被拒，状态保留可重试
            assertThatThrownBy(() -> fixture.controller.updateMeta(task))
                    .isInstanceOf(IllegalStateException.class);
            fixture.controller.transfer(task);
            Region merged = fixture.controller.updateMeta(task);
            assertThat(merged).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void tombstonePhaseCompletesTask() {
        Fixture fixture = fixture();
        try {
            MergeTask task = fixture.controller.beginMerge(
                    new RegionId(1), new RegionId(2),
                    fixture.left, fixture.right);
            fixture.controller.transfer(task);
            fixture.controller.updateMeta(task);
            fixture.controller.tombstone(task);
            assertThat(task.phase()).isEqualTo(MergeTask.MergePhase.TOMBSTONE);
            assertThat(fixture.controller.lifecycleState(new RegionId(13)))
                    .isEqualTo(RegionLifecycleState.NORMAL);
        } finally {
            fixture.close();
        }
    }

    private static Fixture fixture() {
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        for (int i = 0; i < 50; i++) {
            left.put(bytes("k" + String.format("%04d", i)), bytes("v"));
        }
        for (int i = 50; i < 100; i++) {
            right.put(bytes("k" + String.format("%04d", i)), bytes("v"));
        }
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("k0050"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("k0050"), bytes("z"),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n2");
        MergeController controller = new MergeController(regions);
        return new Fixture(controller, regions, left, right);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(MergeController controller,
                           RegionManager regions,
                           MemTable left,
                           MemTable right) implements AutoCloseable {

        @Override
        public void close() {
            left.close();
            right.close();
        }
    }
}
