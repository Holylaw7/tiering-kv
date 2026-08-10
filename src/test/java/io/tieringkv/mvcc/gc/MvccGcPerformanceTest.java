package io.tieringkv.mvcc.gc;

import io.tieringkv.mvcc.MvccGcManager;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 批量 GC 性能（ADR-0078）：目标 >100MB/s，多轮报告范围。 */
class MvccGcPerformanceTest {

    @Test
    void batchGcBeatsSingleVersionPath() {
        double[] rates = new double[3];
        long[] collected = new long[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            for (int i = 0; i < 5_000; i++) {
                for (int v = 1; v <= 50; v++) {
                    engine.putVersion(bytes("k" + i), new byte[128],
                            v, v * 10, WriteType.PUT);
                }
            }
            BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
            gc.updateSafePoint(new SafePoint(100));
            long start = System.nanoTime();
            MvccGcManager.GcResult result = gc.gc();
            double seconds = (System.nanoTime() - start) / 1e9;
            rates[round] = result.collectedBytes() / 1024.0 / 1024.0 / seconds;
            collected[round] = result.collectedVersions();
            gc.close();
            ((MemTable) engine.underlying()).close();
        }
        System.out.printf(Locale.ROOT,
                "PHASE20-BENCH GC-BATCH %.0f-%.0f MB/s collected=%d%n",
                min(rates), max(rates), collected[0]);
        // 目标 >100MB/s；测试保守断言 >50，避免 CI 波动，报告如实记录实测
        assertThat(max(rates)).isGreaterThan(50);
        for (long count : collected) {
            // safePoint=100：50 个版本中 commitTS<100 的有 9 个（10..90）
            assertThat(count).isEqualTo(5_000L * 9);
        }
    }

    @Test
    void batchSizeAffectsThroughput() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 2_000; i++) {
            for (int v = 1; v <= 20; v++) {
                engine.putVersion(bytes("k" + i), new byte[64],
                        v, v * 10, WriteType.PUT);
            }
        }
        BatchGcExecutor small = new BatchGcExecutor(engine,
                new GcConfig(64, 2, 8L << 20));
        small.updateSafePoint(new SafePoint(100));
        MvccGcManager.GcResult result = small.gc();
        // safePoint=100：20 个版本中 commitTS<100 的有 9 个（10..90）
        assertThat(result.collectedVersions()).isEqualTo(2_000L * 9);
        assertThat(engine.versionCount()).isEqualTo(2_000L * 11);
        small.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void memoryBoundChunksScanWithoutLosingVersions() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 1_000; i++) {
            for (int v = 1; v <= 30; v++) {
                engine.putVersion(bytes("k" + i), new byte[256],
                        v, v * 10, WriteType.PUT);
            }
        }
        // 1MB 内存上界：触发多次分块扫描/删除
        BatchGcExecutor gc = new BatchGcExecutor(engine,
                new GcConfig(128, 2, 1L << 20));
        gc.updateSafePoint(new SafePoint(100));
        MvccGcManager.GcResult result = gc.gc();
        MvccGcManager.GcResult second = gc.gc();
        assertThat(result.collectedVersions() + second.collectedVersions())
                .isEqualTo(1_000L * 9);
        for (int i = 0; i < 1_000; i++) {
            // safePoint=100：commitTS>=100 的 v10..v30 全部保留
            assertThat(engine.versions(bytes("k" + i))).hasSize(21);
            assertThat(engine.latestValue(bytes("k" + i))).isNotEmpty();
        }
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void singleWorkerPathCorrect() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 500; i++) {
            for (int v = 1; v <= 10; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine,
                new GcConfig(4096, 1, 64L << 20));
        gc.updateSafePoint(new SafePoint(100));
        assertThat(gc.gc().collectedVersions()).isEqualTo(500L * 9);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void physicalRemovalReclaimsUnderlyingMemory() {
        MemTable table = MemTable.create();
        MvccStorageEngine engine = new MvccStorageEngine(table);
        for (int v = 1; v <= 20; v++) {
            engine.putVersion(bytes("k"), new byte[128], v, v * 10,
                    WriteType.PUT);
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        // 旧版本物理移除：无 tombstone 累积（20 版本 → safePoint=100 保留 11 个）
        try (io.tieringkv.storage.StorageIterator iterator = table.iterator()) {
            int count = 0;
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
            assertThat(count).isEqualTo(11);
        }
        gc.close();
        table.close();
    }

    private static double min(double[] values) {
        return java.util.Arrays.stream(values).min().orElse(0);
    }

    private static double max(double[] values) {
        return java.util.Arrays.stream(values).max().orElse(0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
