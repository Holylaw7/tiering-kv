package io.tieringkv.benchmark.production;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.io.MmapSSTableReader;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产级 Benchmark 基线（ADR-0218）：A 内存引擎 / B 服务端命令链 /
 * C 全链路（WAL + SSTable + mmap）。本地进程内口径，对比 TiKV 时注明来源。
 */
class Phase43ProductionBaselineTest {

    private static final long P99_TARGET_MICROS_A = 5000;
    private static final long P99_TARGET_MICROS_B = 10_000;
    private static final long P99_TARGET_MICROS_C = 50_000;

    @TempDir
    Path dir;

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelAMemoryGetLatencyPercentiles(int ops) {
        MemTable table = memTable();
        byte[][] keys = seed(table, ops);
        long[] samples = new long[ops];
        for (int i = 0; i < ops; i++) {
            long start = System.nanoTime();
            table.get(keys[i]);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        report("A-MEM-GET", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(P99_TARGET_MICROS_A);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelAMemoryPutThroughput(int ops) {
        MemTable table = memTable();
        byte[] value = new byte[64];
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            table.put(("k" + i).getBytes(StandardCharsets.UTF_8),
                    value);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE43-BASELINE A-PUT %d -> %d ops/s%n",
                ops, ops * 1_000L / elapsedMs);
        assertThat(ops * 1_000L / elapsedMs).isGreaterThan(50_000);
    }

    @Test
    void levelAMemoryP99BelowTarget() {
        MemTable table = memTable();
        byte[][] keys = seed(table, 1000);
        long[] samples = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            table.get(keys[i]);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        assertThat(percentile(samples, 0.99))
                .isLessThan(P99_TARGET_MICROS_A);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelBCommandGetLatencyPercentiles(int ops) {
        MemTable table = memTable();
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefault(), table);
        for (int i = 0; i < ops; i++) {
            engine.execute(new RespCommand("set", List.of(
                    ("k" + i).getBytes(StandardCharsets.UTF_8),
                    new byte[32])));
        }
        long[] samples = new long[ops];
        for (int i = 0; i < ops; i++) {
            long start = System.nanoTime();
            engine.execute(new RespCommand("get", List.of(
                    ("k" + i).getBytes(StandardCharsets.UTF_8))));
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        report("B-CMD-GET", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(P99_TARGET_MICROS_B);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelBCommandSetThroughput(int ops) {
        MemTable table = memTable();
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefault(), table);
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            engine.execute(new RespCommand("set", List.of(
                    ("k" + i).getBytes(StandardCharsets.UTF_8),
                    new byte[32])));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE43-BASELINE B-SET %d -> %d ops/s%n",
                ops, ops * 1_000L / elapsedMs);
        assertThat(ops * 1_000L / elapsedMs).isGreaterThan(30_000);
    }

    @Test
    void levelBCommandP99BelowTarget() {
        MemTable table = memTable();
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefault(), table);
        engine.execute(new RespCommand("set", List.of(
                "k".getBytes(StandardCharsets.UTF_8),
                new byte[32])));
        long[] samples = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            engine.execute(new RespCommand("get", List.of(
                    "k".getBytes(StandardCharsets.UTF_8))));
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        assertThat(percentile(samples, 0.99))
                .isLessThan(P99_TARGET_MICROS_B);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelCFullChainPutThroughput(int ops) throws Exception {
        WALConfig config = new WALConfig(dir, 64L * 1024 * 1024,
                WALConfig.FsyncPolicy.NO);
        MemTable table = memTable();
        try (WALManager wal = new WALManager(config)) {
            WALStorageEngine storage =
                    new WALStorageEngine(wal, table);
            long start = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                storage.put(
                        ("k" + i).getBytes(StandardCharsets.UTF_8),
                        new byte[32]);
            }
            long elapsedMs = Math.max(1,
                    (System.nanoTime() - start) / 1_000_000);
            System.out.printf(
                    "PHASE43-BASELINE C-WAL %d -> %d ops/s%n",
                    ops, ops * 1_000L / elapsedMs);
            assertThat(ops * 1_000L / elapsedMs)
                    .isGreaterThan(20_000);
        }
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelCColdReadLatencyPercentiles(int ops) throws Exception {
        SSTableMeta meta = writeSSTable(ops);
        long[] samples = new long[ops];
        try (MmapSSTableReader reader =
                     MmapSSTableReader.open(meta, dir)) {
            for (int i = 0; i < ops; i++) {
                byte[] key = ("k" + i)
                        .getBytes(StandardCharsets.UTF_8);
                long start = System.nanoTime();
                reader.get(key);
                samples[i] = (System.nanoTime() - start) / 1000;
            }
        }
        report("C-MMAP-GET", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(P99_TARGET_MICROS_C);
    }

    @Test
    void levelCFullChainP99BelowTarget() throws Exception {
        SSTableMeta meta = writeSSTable(1000);
        long[] samples = new long[1000];
        try (MmapSSTableReader reader =
                     MmapSSTableReader.open(meta, dir)) {
            for (int i = 0; i < 1000; i++) {
                long start = System.nanoTime();
                reader.get(("k" + i)
                        .getBytes(StandardCharsets.UTF_8));
                samples[i] = (System.nanoTime() - start) / 1000;
            }
        }
        assertThat(percentile(samples, 0.99))
                .isLessThan(P99_TARGET_MICROS_C);
    }

    @ParameterizedTest(name = "quantile {0}")
    @ValueSource(doubles = {0.5, 0.95, 0.99})
    void percentileComputation(double quantile) {
        long[] samples = new long[100];
        Arrays.setAll(samples, i -> i + 1);
        assertThat(percentile(samples, quantile))
                .isEqualTo(samples[(int) (quantile * 99)]);
    }

    @ParameterizedTest(name = "size {0} quantile {1}")
    @CsvSource({
            "1,0.5",
            "2,0.95",
            "10,0.99",
            "100,0.5",
            "100,0.95",
            "100,0.99",
            "1000,0.5",
            "1000,0.99"
    })
    void percentileBoundaryCases(int size, double quantile) {
        long[] samples = new long[size];
        Arrays.setAll(samples, i -> i);
        long value = percentile(samples, quantile);
        assertThat(value).isGreaterThanOrEqualTo(0);
        assertThat(value).isLessThanOrEqualTo(size - 1L);
    }

    @Test
    void tikvComparisonBasisDocumented() throws Exception {
        Path doc = Path.of("docs", "benchmark",
                "production-baseline.md");
        assertThat(doc.toFile()).exists();
        String content = java.nio.file.Files.readString(doc);
        assertThat(content).contains("TiKV");
        assertThat(content).contains("A/B/C");
        assertThat(content).contains("本地进程内");
    }

    @Test
    void memoryBaselineReportsEntryAndEstimate() {
        MemTable table = memTable();
        int ops = 10_000;
        for (int i = 0; i < ops; i++) {
            table.put(("k" + i).getBytes(StandardCharsets.UTF_8),
                    new byte[64]);
        }
        long estimated = table.size() * 96L;
        System.out.printf(
                "PHASE43-BASELINE A-MEM entries=%d estimate=%d bytes%n",
                table.size(), estimated);
        assertThat(table.size()).isEqualTo(ops);
        assertThat(estimated).isLessThan(2L * 1024 * 1024);
    }

    private SSTableMeta writeSSTable(int ops) throws Exception {
        try (SSTableWriter writer = new SSTableWriter(dir, 1,
                ops, 10, 4096)) {
            for (int i = 0; i < ops; i++) {
                writer.writeEntry(KeyValueEntry.live(
                        ("k" + i).getBytes(StandardCharsets.UTF_8),
                        new byte[32], 0, -1, i));
            }
            return writer.finish();
        }
    }

    private static byte[][] seed(MemTable table, int ops) {
        byte[][] keys = new byte[ops][];
        for (int i = 0; i < ops; i++) {
            keys[i] = ("k" + i)
                    .getBytes(StandardCharsets.UTF_8);
            table.put(keys[i], new byte[32]);
        }
        return keys;
    }

    private static MemTable memTable() {
        return MemTable.createForTest(new MutableClock(0),
                new MemoryManager(1 << 30));
    }

    private static void report(String label, long[] samplesMicros) {
        System.out.printf(
                "PHASE43-BASELINE %s p50=%dus p95=%dus p99=%dus%n",
                label, percentile(samplesMicros, 0.5),
                percentile(samplesMicros, 0.95),
                percentile(samplesMicros, 0.99));
    }

    private static long percentile(long[] samples,
                                   double quantile) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        int index = (int) (quantile * (sorted.length - 1));
        return sorted[index];
    }
}
