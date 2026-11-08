package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.tiering.StorageGaugeRefresher;
import io.tieringkv.storage.tiering.StorageMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** G0-5C-2B-2：冷层 pending 只读指标，反映真实状态。 */
class ColdPendingMetricsTest {

    @TempDir
    Path dir;

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void pending为空时指标为零() throws Exception {
        try (ColdStorageEngine cold = new ColdStorageEngine(ColdStorageEngine.Config.defaults(dir))) {
            assertThat(cold.pendingCount()).isZero();
            assertThat(cold.pendingByteSize()).isZero();
        }
    }

    @Test
    void 条目进入pending后计数与字节数增长() throws Exception {
        try (ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1 << 20, 100))) {
            cold.put(KeyValueEntry.live(b("a"), b("1"), 0, -1, 1));
            assertThat(cold.pendingCount()).isEqualTo(1);
            long first = cold.pendingByteSize();
            assertThat(first).isPositive();

            cold.put(KeyValueEntry.live(b("bb"), b("2222"), 0, -1, 2));
            assertThat(cold.pendingCount()).isEqualTo(2);
            assertThat(cold.pendingByteSize()).isGreaterThan(first);
        }
    }

    @Test
    void 达到阈值落表后pending归零且出现SSTable() throws Exception {
        try (ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1, 100))) {
            cold.put(KeyValueEntry.live(b("k"), b("v"), 0, -1, 1));
            assertThat(cold.pendingCount()).isZero();
            assertThat(cold.tablesSnapshot()).hasSize(1);
        }
    }

    @Test
    void 刷新器把pending状态发布到INFO快照() throws Exception {
        try (ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1 << 20, 100))) {
            cold.put(KeyValueEntry.live(b("x"), b("y"), 0, -1, 1));
            StorageMetrics metrics = new StorageMetrics();
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> false);
            refresher.refreshOnce();

            StorageMetrics.Snapshot snapshot = metrics.snapshot();
            assertThat(snapshot.coldPendingEntries()).isEqualTo(1);
            assertThat(snapshot.coldPendingBytes()).isPositive();
        }
    }

    @Test
    void INFO读取pending指标不触发落表() throws Exception {
        try (ColdStorageEngine cold = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1 << 20, 100))) {
            cold.put(KeyValueEntry.live(b("x"), b("y"), 0, -1, 1));
            StorageMetrics metrics = new StorageMetrics();
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> false);
            refresher.refreshOnce();
            // 刷新器本身会复制一次表清单；此后 INFO 侧只读快照，不应再复制
            long afterRefresh = cold.tablesSnapshotCalls();
            for (int i = 0; i < 50; i++) {
                metrics.snapshot();
            }
            assertThat(cold.pendingCount()).isEqualTo(1);
            assertThat(cold.tablesSnapshotCalls()).isEqualTo(afterRefresh);
        }
    }
}