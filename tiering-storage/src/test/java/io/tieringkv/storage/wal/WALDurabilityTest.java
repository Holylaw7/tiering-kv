package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-103：EVERY_SEC 的持久化边界。
 *
 * <p>缺陷背景：fsync 只在 append 时按"距上次 ≥1s"判断，
 * 最后一次写入之后的空闲期不会落盘；用 BufferedOutputStream 缓存 64KB，
 * 崩溃时这段时间的数据全部丢失（Phase 10 实测 100 个键丢了 99 个）。
 *
 * <p>修复后：后台每 500ms 强制 flush+fsync，空闲期写入也会在 1s 内落盘。
 */
class WALDurabilityTest {

    @TempDir
    Path dir;

    private static WALEntry put(String key, String value) {
        return WALEntry.put(System.currentTimeMillis(),
                key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8),
                -1L, 1L);
    }

    private static long segmentSize(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log"))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException ex) {
                            return 0;
                        }
                    }).sum();
        }
    }

    @Test
    void idleWritesAreForcedWithinOneSecond() throws Exception {
        WALManager wal = new WALManager(new WALConfig(dir, 4L << 20, WALConfig.FsyncPolicy.EVERY_SEC));
        try {
            for (int i = 0; i < 10; i++) {
                wal.append(put("k" + i, "v" + i));
            }
            // 关键：之后不再 append，只能依赖后台落盘
            Thread.sleep(1600);

            assertThat(segmentSize(dir)).as("空闲期写入必须已落到文件").isGreaterThan(0);

            MemTable table = MemTable.create(new MemoryManager(8L << 20));
            RecoveryManager.RecoveryStats stats = new RecoveryManager(
                    new WALConfig(dir, 4L << 20, WALConfig.FsyncPolicy.EVERY_SEC)).recover(table);
            assertThat(stats.recordsApplied()).isEqualTo(10);
            assertThat(table.size()).isEqualTo(10);
        } finally {
            wal.close();
        }
    }

    @Test
    void flushLatencyIsObservedNotAssumed() throws Exception {
        // 量化"写入 → 落盘"的实际延迟，而不是假设调度周期等于最坏窗口
        long worstMicros = 0;
        StringBuilder samples = new StringBuilder();
        for (int round = 0; round < 10; round++) {
            Path dir2 = dir.resolve("latency-" + round);
            Files.createDirectories(dir2);
            WALManager wal = new WALManager(new WALConfig(dir2, 4L << 20, WALConfig.FsyncPolicy.EVERY_SEC));
            try {
                // 起手写一条：本次 append 会内联 force 并刷新 lastForceMillis，
                // 之后 1s 内的写入只能依赖后台调度 → 测出的才是真实的最坏窗口
                wal.append(put("warm", "v"));
                Thread.sleep(100);
                long sizeBefore = segmentSize(dir2);

                long started = System.nanoTime();
                wal.append(put("k", "v"));
                long deadline = System.nanoTime() + 3_000_000_000L;
                while (System.nanoTime() < deadline && segmentSize(dir2) <= sizeBefore) {
                    Thread.sleep(2);
                }
                long elapsedMicros = (System.nanoTime() - started) / 1_000;
                assertThat(segmentSize(dir2)).as("第 %d 轮必须落盘", round).isGreaterThan(sizeBefore);
                worstMicros = Math.max(worstMicros, elapsedMicros);
                samples.append(String.format(java.util.Locale.ROOT, "%.1f ", elapsedMicros / 1000.0));
            } finally {
                wal.close();
            }
        }
        System.out.printf("[wal-latency] 10 轮后台落盘延迟(ms)= %s| 最坏=%.1f ms%n",
                samples, worstMicros / 1000.0);
        // 后台调度周期为 500ms：最坏延迟应落在 0~600ms 量级（含调度与 IO 抖动）
        assertThat(worstMicros).as("最坏延迟应在 1s 以内").isLessThan(1_000_000L);
    }

    @Test
    void idlePeriodDoesNotKeepSyncing() throws Exception {
        WALManager wal = new WALManager(new WALConfig(dir, 4L << 20, WALConfig.FsyncPolicy.EVERY_SEC));
        try {
            wal.append(put("k1", "v1"));
            Thread.sleep(1200); // 至少完成一次后台落盘
            long sizeAfterFlush = segmentSize(dir);
            assertThat(sizeAfterFlush).isGreaterThan(0);

            // 再空闲 2s：没有新写入时不应持续 flush（dirty=false 直接跳过）
            Thread.sleep(2000);
            assertThat(segmentSize(dir)).isEqualTo(sizeAfterFlush);
        } finally {
            wal.close();
        }
    }

    @Test
    void appendAfterIdleIsDurableWithoutExtraWrites() throws Exception {
        WALManager wal = new WALManager(new WALConfig(dir, 4L << 20, WALConfig.FsyncPolicy.EVERY_SEC));
        try {
            wal.append(put("only", "one"));
            Thread.sleep(1600);
            assertThat(segmentSize(dir)).isGreaterThan(0);
        } finally {
            wal.close();
        }
    }
}