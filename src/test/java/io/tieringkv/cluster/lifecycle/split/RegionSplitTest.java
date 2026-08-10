package io.tieringkv.cluster.lifecycle.split;

import io.tieringkv.cluster.lifecycle.RegionLifecycleState;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.cluster.region.StaleRegionEpochException;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Region 分裂生命周期（ADR-0061）：状态机 / 五阶段 / 并发写不丢失。 */
class RegionSplitTest {

    @Test
    void beginSplitValidatesNormalState() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            assertThat(task.phase()).isEqualTo(RegionSplitTask.SplitPhase.PREPARE);
            assertThat(fixture.regions.get(new RegionId(1)).state())
                    .isEqualTo(RegionState.SPLITTING);
        } finally {
            fixture.close();
        }
    }

    @Test
    void beginSplitRejectsTombstonedRegion() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            fixture.regions.splitRegion(new RegionId(1), bytes("m"));
            assertThatThrownBy(() -> fixture.controller.beginSplit(
                    new RegionId(1), bytes("q"), fixture.source,
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void beginSplitRejectsOpenEndedRegion() {
        Fixture fixture = fixture(10, "a", null);
        try {
            assertThatThrownBy(() -> fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void beginSplitRejectsKeyOutsideRange() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            assertThatThrownBy(() -> fixture.controller.beginSplit(
                    new RegionId(1), bytes("0"), fixture.source,
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> fixture.controller.beginSplit(
                    new RegionId(1), bytes("z"), fixture.source,
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void snapshotPartitionsBySplitKey() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            SplitSnapshot snapshot = fixture.controller.snapshot(task);
            assertThat(snapshot.left()).hasSize(50);
            assertThat(snapshot.right()).hasSize(50);
            assertThat(snapshot.left().get(0).key()).isEqualTo(bytes("k0000"));
            assertThat(snapshot.right().get(0).key()).isEqualTo(bytes("k0050"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void snapshotChecksumNonZero() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            SplitSnapshot snapshot = fixture.controller.snapshot(task);
            assertThat(snapshot.checksum()).isNotZero();
        } finally {
            fixture.close();
        }
    }

    @Test
    void snapshotBarrierVersionSet() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            SplitSnapshot snapshot = fixture.controller.snapshot(task);
            assertThat(snapshot.barrierVersion()).isGreaterThan(0);
        } finally {
            fixture.close();
        }
    }

    @Test
    void snapshotWithoutSourceReturnsEmptyBothSides() {
        Fixture fixture = fixture(0, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            SplitSnapshot snapshot = fixture.controller.snapshot(task);
            assertThat(snapshot.size()).isZero();
        } finally {
            fixture.close();
        }
    }

    @Test
    void installLoadsLeftStorage() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            assertThat(fixture.left.size()).isEqualTo(50);
            assertThat(fixture.left.get(bytes("k0000"))).isNotNull();
            assertThat(fixture.left.get(bytes("k0049"))).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void installLoadsRightStorage() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            assertThat(fixture.right.size()).isEqualTo(50);
            assertThat(fixture.right.get(bytes("k0050"))).isNotNull();
            assertThat(fixture.right.get(bytes("k0099"))).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void installCreatesTwoChildren() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            List<Region> children = fixture.controller.install(task);
            assertThat(children).hasSize(2);
            assertThat(fixture.regions.regionCount()).isEqualTo(2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void installAdvancesEpoch() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            List<Region> children = fixture.controller.install(task);
            assertThat(children.get(0).epoch().confVer()).isEqualTo(2);
            assertThat(children.get(1).epoch().confVer()).isEqualTo(2);
        } finally {
            fixture.close();
        }
    }

    @Test
    void installTombstonesParent() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            assertThat(fixture.regions.get(new RegionId(1)).state())
                    .isEqualTo(RegionState.TOMBSTONE);
        } finally {
            fixture.close();
        }
    }

    @Test
    void installWithoutSnapshotRejected() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            assertThatThrownBy(() -> fixture.controller.install(task))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void commitAppliesBufferedWritesToLeft() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            fixture.controller.bufferWrite(new RegionId(1),
                    bytes("k-new-left"), bytes("v"), 1, -1);
            fixture.controller.commit(task);
            assertThat(fixture.left.get(bytes("k-new-left"))).isEqualTo(bytes("v"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void commitAppliesBufferedWritesToRight() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k0050"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            fixture.controller.bufferWrite(new RegionId(1),
                    bytes("zz-new"), bytes("v"), 1, -1);
            fixture.controller.commit(task);
            assertThat(fixture.right.get(bytes("zz-new"))).isEqualTo(bytes("v"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void commitDrainsBuffer() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            fixture.controller.bufferWrite(new RegionId(1),
                    bytes("k1"), bytes("v"), 1, -1);
            assertThat(task.writeBuffer().size()).isEqualTo(1);
            fixture.controller.commit(task);
            assertThat(task.writeBuffer().size()).isZero();
        } finally {
            fixture.close();
        }
    }

    @Test
    void cleanupRemovesActiveTask() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.cleanup(task);
            assertThat(fixture.controller.lifecycleState(new RegionId(1)))
                    .isEqualTo(RegionLifecycleState.NORMAL);
            assertThat(task.phase()).isEqualTo(RegionSplitTask.SplitPhase.CLEANUP);
        } finally {
            fixture.close();
        }
    }

    @Test
    void cleanupNullsSnapshot() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.cleanup(task);
            assertThat(task.snapshot()).isNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void oneShotSplitEndToEnd() {
        Fixture fixture = fixture(200, "a", "z");
        try {
            List<Region> children = fixture.controller.split(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            assertThat(children).hasSize(2);
            assertThat(fixture.left.size() + fixture.right.size()).isEqualTo(200);
            assertThat(fixture.regions.regionCount()).isEqualTo(2);
            assertThat(fixture.controller.lifecycleState(new RegionId(1)))
                    .isEqualTo(RegionLifecycleState.TOMBSTONE);
        } finally {
            fixture.close();
        }
    }

    @Test
    void splitDuringWritesNoLostData() throws Exception {
        Fixture fixture = fixture(5_000, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("k5000"), fixture.source,
                    fixture.left, fixture.right);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            // 分裂窗口内并发写入（缓冲）
            AtomicBoolean done = new AtomicBoolean(false);
            Thread writer = new Thread(() -> {
                for (int i = 5_000; i < 10_000; i++) {
                    fixture.controller.bufferWrite(new RegionId(1),
                            bytes("k" + i), bytes("v"), i, -1);
                }
                done.set(true);
            });
            writer.start();
            writer.join(10_000);
            assertThat(done.get()).isTrue();
            fixture.controller.commit(task);
            fixture.controller.cleanup(task);
            // 无数据丢失：左 5000 + 右 5000（缓冲）
            assertThat(fixture.left.size()).isEqualTo(5_000);
            assertThat(fixture.right.size()).isEqualTo(5_000);
            assertThat(fixture.left.get(bytes("k0000"))).isNotNull();
            assertThat(fixture.left.get(bytes("k4999"))).isNotNull();
            assertThat(fixture.right.get(bytes("k5000"))).isNotNull();
            assertThat(fixture.right.get(bytes("k9999"))).isNotNull();
        } finally {
            fixture.close();
        }
    }

    @Test
    void lifecycleStateDuringPhases() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            RegionSplitTask task = fixture.controller.beginSplit(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            assertThat(fixture.controller.lifecycleState(new RegionId(1)))
                    .isEqualTo(RegionLifecycleState.SPLITTING);
            fixture.controller.snapshot(task);
            fixture.controller.install(task);
            assertThat(fixture.controller.lifecycleState(new RegionId(1)))
                    .isEqualTo(RegionLifecycleState.SPLIT_READY);
        } finally {
            fixture.close();
        }
    }

    @Test
    void bufferWriteOutsideSplitRejected() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            assertThatThrownBy(() -> fixture.controller.bufferWrite(
                    new RegionId(1), bytes("k"), bytes("v"), 1, -1))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void splitThenRouteWorks() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            fixture.controller.split(new RegionId(1), bytes("k0050"),
                    fixture.source, fixture.left, fixture.right);
            assertThat(fixture.regions.route(bytes("k0001")).regionId().id())
                    .isEqualTo(11);
            assertThat(fixture.regions.route(bytes("k0099")).regionId().id())
                    .isEqualTo(12);
        } finally {
            fixture.close();
        }
    }

    @Test
    void childrenInheritLeader() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            List<Region> children = fixture.controller.split(
                    new RegionId(1), bytes("m"), fixture.source,
                    fixture.left, fixture.right);
            assertThat(children.get(0).leader()).isEqualTo("n1");
            assertThat(children.get(1).leader()).isEqualTo("n1");
        } finally {
            fixture.close();
        }
    }

    @Test
    void oldEpochRejectedAfterSplit() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            RegionEpoch initial = fixture.regions.get(new RegionId(1)).epoch();
            fixture.controller.split(new RegionId(1), bytes("m"),
                    fixture.source, fixture.left, fixture.right);
            assertThatThrownBy(() -> fixture.regions.routeStrict(
                    bytes("k0001"), initial))
                    .isInstanceOf(StaleRegionEpochException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void concurrentSplitRejectedWhileActive() {
        Fixture fixture = fixture(100, "a", "z");
        try {
            fixture.controller.beginSplit(new RegionId(1), bytes("m"),
                    fixture.source, fixture.left, fixture.right);
            assertThatThrownBy(() -> fixture.controller.beginSplit(
                    new RegionId(1), bytes("q"), fixture.source,
                    fixture.left, fixture.right))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            fixture.close();
        }
    }

    @Test
    void splitPreservesAllSourceEntries() {
        Fixture fixture = fixture(1_000, "a", "z");
        try {
            fixture.controller.split(new RegionId(1), bytes("m"),
                    fixture.source, fixture.left, fixture.right);
            assertThat(fixture.left.size() + fixture.right.size()).isEqualTo(1_000);
        } finally {
            fixture.close();
        }
    }

    @Test
    void splitPreservesTtl() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            fixture.source.put(bytes("k0001"), bytes("v"), 10_000);
            fixture.controller.split(new RegionId(1), bytes("m"),
                    fixture.source, fixture.left, fixture.right);
            assertThat(fixture.left.getEntry(bytes("k0001")).expireTimestamp())
                    .isGreaterThan(0);
        } finally {
            fixture.close();
        }
    }

    @Test
    void splitEmptyLeftSide() {
        Fixture fixture = fixture(10, "a", "z");
        try {
            // 所有键都在 splitKey 右侧（splitKey = "k0000" 之外的更小值不可行，
            // 使用区间内最小键"a"附近：splitKey "k0000" 严格大于"a"）
            fixture.controller.split(new RegionId(1), bytes("k0000"),
                    fixture.source, fixture.left, fixture.right);
            assertThat(fixture.left.size()).isZero();
            assertThat(fixture.right.size()).isEqualTo(10);
        } finally {
            fixture.close();
        }
    }

    private static Fixture fixture(int count, String start, String end) {
        MemTable source = MemTable.create();
        for (int i = 0; i < count; i++) {
            source.put(bytes("k" + String.format("%04d", i)), bytes("v"));
        }
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1),
                bytes(start), end == null ? null : bytes(end),
                List.of("n1", "n2", "n3"), RegionEpoch.INITIAL, "n1");
        SplitController controller = new SplitController(regions);
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        return new Fixture(controller, regions, source, left, right);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(SplitController controller,
                           RegionManager regions,
                           MemTable source,
                           MemTable left,
                           MemTable right) implements AutoCloseable {

        @Override
        public void close() {
            source.close();
            left.close();
            right.close();
        }
    }
}
