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