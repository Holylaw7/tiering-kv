package io.tieringkv.benchmark.wal;

import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.RecoveryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALEntry;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WAL 基准（Phase 4）：append 100K/1M 的 P50/P95/P99 与吞吐（EVERY_SEC，
 * 近似 group commit）；恢复 100K/1M 耗时。目标：append P99 &lt; 1ms，恢复秒级。
 */
@Tag("benchmark")
class WALBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void appendLatencyAndThroughput() throws Exception {
        measureAppend(100_000);
        measureAppend(1_000_000);
    }

    @Test
    void recoveryTime() throws Exception {
        for (int count : new int[]{100_000, 1_000_000}) {
            Path subDir = dir.resolve("recovery-" + count);
            WALConfig config = WALConfig.defaults(subDir);
            try (WALManager wal = new WALManager(config)) {
                for (int i = 0; i < count; i++) {
                    wal.append(entry(i));
                }
            }
            MemTable memTable = MemTable.createForTest(
                    System::currentTimeMillis, new MemoryManager(1L << 31));
            long start = System.nanoTime();
            RecoveryManager.RecoveryStats stats = new RecoveryManager(config).recover(memTable);
            double millis = (System.nanoTime() - start) / 1_000_000.0;
            System.out.printf(Locale.ROOT,
                    "WAL-BENCH RECOVERY records=%d time=%.1fms applied=%d size=%d%n",
                    count, millis, stats.recordsApplied(), memTable.size());
            assertThat(millis).as("recovery time records=%d", count).isLessThan(30_000);
        }
    }

    private void measureAppend(int count) throws Exception {
        WALConfig config = WALConfig.defaults(dir.resolve("append-" + count));
        try (WALManager wal = new WALManager(config)) {
            for (int i = 0; i < 1000; i++) {
                wal.append(entry(i));
            }
            long[] latencies = new long[count];
            long start = System.nanoTime();
            for (int i = 0; i < count; i++) {
                long t0 = System.nanoTime();
                wal.append(entry(i));
                latencies[i] = System.nanoTime() - t0;
            }
            long totalNanos = System.nanoTime() - start;
            Arrays.sort(latencies);
            double p50 = latencies[count / 2] / 1_000_000.0;
            double p95 = latencies[(int) (count * 0.95)] / 1_000_000.0;
            double p99 = latencies[(int) (count * 0.99)] / 1_000_000.0;
            double opsPerSecond = count / (totalNanos / 1_000_000_000.0);
            System.out.printf(Locale.ROOT,
                    "WAL-BENCH APPEND records=%d P50=%.4fms P95=%.4fms P99=%.4fms throughput=%.0f ops/s%n",
                    count, p50, p95, p99, opsPerSecond);
            assertThat(p99).as("append P99 records=%d", count).isLessThan(1.0);
        }
    }

    private static WALEntry entry(int i) {
        return WALEntry.put(i,
                String.format(Locale.ROOT, "wal-key:%08d", i).getBytes(StandardCharsets.UTF_8),
                new byte[8], -1, i);
    }
}
