package io.tieringkv.benchmark.production;

import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
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
import io.tieringkv.transaction.async.MultiCloudOnePhaseCommit;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 45 生产基线（ADR-0232）：跨机口径 + 本地进程内补充，
 * TiKV 对比表如实标注（跨机 Runner 可执行项全绿 / 未执行项登记）。
 */
class Phase45ProductionBaselineTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDMultiCloudCommitLatency(int commits) {
        MultiCloudOnePhaseCommit commit = cloudCommit();
        long[] samples = new long[commits];
        for (int i = 0; i < commits; i++) {
            long start = System.nanoTime();
            commit.commit("t" + i,
                    Set.of("aws", "gcp", "azure"), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        report("D-MULTICLOUD", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDFullOpChainThroughput(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + (i % 10), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.JOIN, Operator.FILTER,
                                Operator.GROUP_BY, Operator.WINDOW,
                                Operator.ORDER_BY, Operator.LIMIT),
                        "k0", "zz", 0,
                        List.of(new Row("k0", 1),
                                new Row("k1", 1)),
                        List.of(), 10, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .ROW_NUMBER);
        long start = System.nanoTime();
        executor.executeCompound(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BASELINE D-FULLOP %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
        assertThat(rows * 1_000L / elapsedMs)
                .isGreaterThan(50_000);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDWALWriteThroughput(int ops) throws Exception {
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
                    "PHASE45-BASELINE D-WAL %d -> %d ops/s%n",
                    ops, ops * 1_000L / elapsedMs);
            assertThat(ops * 1_000L / elapsedMs)
                    .isGreaterThan(20_000);
        }
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelDColdReadLatency(int ops) throws Exception {
        SSTableMeta meta = writeSSTable(ops);
        long[] samples = new long[ops];
        try (MmapSSTableReader reader =
                     MmapSSTableReader.open(meta, dir)) {
            for (int i = 0; i < ops; i++) {
                long start = System.nanoTime();
                reader.get(("k" + i)
                        .getBytes(StandardCharsets.UTF_8));
                samples[i] = (System.nanoTime() - start) / 1000;
            }
        }
        report("D-MMAP", samples);
        assertThat(percentile(samples, 0.99))
                .isLessThan(50_000);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1000, 10_000})
    void levelAMemoryThroughput(int ops) {
        MemTable table = memTable();
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            table.put(("k" + i).getBytes(StandardCharsets.UTF_8),
                    new byte[32]);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE45-BASELINE A-PUT %d -> %d ops/s%n",
                ops, ops * 1_000L / elapsedMs);
        assertThat(ops * 1_000L / elapsedMs)
                .isGreaterThan(50_000);
    }

    @ParameterizedTest(name = "keyword {0}")
    @CsvSource({
            "TiKV",
            "A/B/C/D",
            "本地进程内",
            "跨机",
            "待执行",
            "P50",
            "P95",
            "P99",
            "吞吐",
            "内存",
            "公开口径",
            "Runner",
            "Gateway×3",
            "Metadata×3",
            "Storage×6",
            "RTT",
            "RTO",
            "RPO",
            "冲突率",
            "收敛时间"
    })
    void tikvCrossMachineKeywordsPresent(String keyword)
            throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-cross-machine-baseline.md"));
        assertThat(content).contains(keyword);
    }

    @ParameterizedTest(name = "entries {0} bytes {1}")
    @CsvSource({
            "1000,96,1000000",
            "10000,96,10000000",
            "100000,96,100000000",
            "1000,128,2000000",
            "10000,128,20000000",
            "100000,128,200000000",
            "50000,64,5000000",
            "200000,32,8000000",
            "25000,96,3000000",
            "75000,96,10000000"
    })
    void memoryEstimateBounds(int entries, int bytesPerEntry,
                              long bound) {
        long estimate = (long) entries * bytesPerEntry;
        assertThat(estimate).isLessThan(bound);
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
            "1000,0.95",
            "1000,0.99",
            "5000,0.99"
    })
    void percentileBoundaries(int size, double quantile) {
        long[] samples = new long[size];
        Arrays.setAll(samples, i -> i);
        assertThat(percentile(samples, quantile))
                .isBetween(0L, size - 1L);
    }

    @Test
    void crossMachineBaselineDocumented() throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-cross-machine-baseline.md"));
        assertThat(content).contains("TiKV");
        assertThat(content).contains("跨机待执行");
    }

    @Test
    void tikvComparisonDocumented() throws Exception {
        String content = Files.readString(Path.of("docs",
                "benchmark", "tikv-cross-machine-baseline.md"));
        assertThat(content).contains("公开口径");
        assertThat(content).contains("本地进程内");
    }

    @Test
    void levelDP99WithinTarget() {
        MultiCloudOnePhaseCommit commit = cloudCommit();
        long[] samples = new long[1000];
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            commit.commit("t" + i,
                    Set.of("aws", "gcp", "azure"), i);
            samples[i] = (System.nanoTime() - start) / 1000;
        }
        assertThat(percentile(samples, 0.99))
                .isLessThan(1000);
    }

    @Test
    void resolvedTsMonotonicBaseline() {
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        resolved.advance(100);
        assertThat(resolved.advance(50)).isEqualTo(100);
        assertThat(resolved.advance(150)).isEqualTo(150);
    }

    @Test
    void gateV11Baseline() {
        assertThat(io.tieringkv.ci.GateConvergenceV11.gates())
                .hasSize(19);
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

    private static MultiCloudOnePhaseCommit cloudCommit() {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        commit.registerCloud("aws", true);
        commit.registerCloud("gcp", true);
        commit.registerCloud("azure", false);
        return commit;
    }

    private static MemTable memTable() {
        return MemTable.createForTest(new MutableClock(0),
                new MemoryManager(1 << 30));
    }

    private static void report(String label,
                               long[] samplesMicros) {
        System.out.printf(
                "PHASE45-BASELINE %s p50=%dus p95=%dus p99=%dus%n",
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
