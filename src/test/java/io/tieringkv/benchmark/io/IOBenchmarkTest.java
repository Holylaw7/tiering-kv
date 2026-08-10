package io.tieringkv.benchmark.io;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.io.FileChannelSSTableReader;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.io.MmapSSTableReader;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IO 基准（Phase 8）：mmap vs FileChannel（100K/1M 随机 + 顺序读）、
 * BlockCache 冷/热/混合、内存池与 GC 概况。
 */
@Tag("benchmark")
class IOBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void mmapVersusFileChannel() throws Exception {
        for (int count : new int[]{100_000, 1_000_000}) {
            Path sub = dir.resolve("reader-" + count);
            Files.createDirectories(sub);
            SSTableMeta meta;
            try (SSTableWriter writer = new SSTableWriter(sub, 1, count, 10, 4096)) {
                for (int i = 0; i < count; i++) {
                    writer.writeEntry(entry(i));
                }
                meta = writer.finish();
            }
            try (FileChannelSSTableReader baseline = FileChannelSSTableReader.open(meta, sub);
                 MmapSSTableReader mmap = MmapSSTableReader.open(meta, sub)) {
                measure("FC-RANDOM", count, i -> baseline.get(key(i)), false);
                measure("MMAP-RANDOM", count, i -> mmap.get(key(i)), false);
                measure("FC-SEQUENTIAL", count, i -> baseline.get(key(i)), true);
                measure("MMAP-SEQUENTIAL", count, i -> mmap.get(key(i)), true);
            }
        }
    }

    @Test
    void blockCacheColdWarmMixed() throws Exception {
        int count = 200_000;
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(
                dir.resolve("cache"), 4096, 10, 1 << 20, 100);
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(4096), pool);
        IOStatistics stats = new IOStatistics();
        try (ColdStorageEngine cold = new ColdStorageEngine(config, cache, stats, true)) {
            cold.writeTable(entries(count));
            // 冷读
            double coldP99 = randomGetP99(cold, count, 20_000);
            // 热读（同一批键）
            double warmP99 = randomGetP99(cold, count, 20_000);
            // 混合：50% 热点键 + 50% 冷键
            double mixedP99 = mixedGetP99(cold, count, 20_000);
            System.out.printf(Locale.ROOT,
                    "IO-BENCH CACHE coldP99=%.3fms warmP99=%.3fms mixedP99=%.3fms hitRate=%.2f%%%n",
                    coldP99, warmP99, mixedP99,
                    cache.statistics().snapshot().hitRate() * 100);
            assertThat(coldP99).isLessThan(5.0);
            assertThat(warmP99).isLessThan(1.0);
            assertThat(cache.statistics().snapshot().hitRate()).isGreaterThan(0.1);
        }
    }

    @Test
    void memoryProfile() throws Exception {
        int count = 100_000;
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(
                dir.resolve("mem"), 4096, 10, 1 << 20, 100);
        MemoryPool pool = new MemoryPool();
        long gcBefore = gcCount();
        try (ColdStorageEngine cold = new ColdStorageEngine(
                config, new BlockCache(new CachePolicy(2048), pool), new IOStatistics(), true)) {
            cold.writeTable(entries(count));
            for (int i = 0; i < 50_000; i++) {
                cold.get(key(ThreadLocalRandom.current().nextInt(count)));
            }
        }
        long gcAfter = gcCount();
        var snapshot = pool.tracker().snapshot();
        System.out.printf(Locale.ROOT,
                "IO-BENCH MEMORY poolAllocated=%dKB reuse=%d peak=%dKB gcDelta=%d%n",
                snapshot.allocatedBytes() / 1024, snapshot.reuseCount(),
                snapshot.peakBytes() / 1024, gcAfter - gcBefore);
        assertThat(snapshot.reuseCount()).isGreaterThanOrEqualTo(0);
    }

    private static void measure(
            String label, int dataset, KeyReader reader, boolean sequential) throws Exception {
        for (int i = 0; i < 2000; i++) {
            reader.read(sequential ? i % dataset : ThreadLocalRandom.current().nextInt(dataset));
        }
        int samples = 20_000;
        long[] latencies = new long[samples];
        long start = System.nanoTime();
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            reader.read(sequential ? i % dataset : ThreadLocalRandom.current().nextInt(dataset));
            latencies[i] = System.nanoTime() - t0;
        }
        long total = System.nanoTime() - start;
        Arrays.sort(latencies);
        double p50 = latencies[samples / 2] / 1_000_000.0;
        double p99 = latencies[(int) (samples * 0.99)] / 1_000_000.0;
        double ops = samples / (total / 1_000_000_000.0);
        System.out.printf(Locale.ROOT,
                "IO-BENCH %s dataset=%d p50=%.3fms p99=%.3fms ops/s=%.0f%n",
                label, dataset, p50, p99, ops);
        assertThat(p99).isLessThan(5.0);
    }

    private static double randomGetP99(ColdStorageEngine cold, int dataset, int samples) {
        long[] latencies = new long[samples];
        for (int i = 0; i < samples; i++) {
            long t0 = System.nanoTime();
            cold.get(key(ThreadLocalRandom.current().nextInt(dataset)));
            latencies[i] = System.nanoTime() - t0;
        }
        Arrays.sort(latencies);
        return latencies[(int) (samples * 0.99)] / 1_000_000.0;
    }

    private static double mixedGetP99(ColdStorageEngine cold, int dataset, int samples) {
        long[] latencies = new long[samples];
        for (int i = 0; i < samples; i++) {
            int index = (i & 1) == 0 ? i % 1000 : 1000 + (i % (dataset - 1000));
            long t0 = System.nanoTime();
            cold.get(key(index));
            latencies[i] = System.nanoTime() - t0;
        }
        Arrays.sort(latencies);
        return latencies[(int) (samples * 0.99)] / 1_000_000.0;
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionCount())
                .sum();
    }

    private static List<KeyValueEntry> entries(int count) {
        List<KeyValueEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(entry(i));
        }
        return entries;
    }

    private static KeyValueEntry entry(int i) {
        return KeyValueEntry.live(key(i), new byte[8], 0, -1, i);
    }

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "io-key:%08d", i).getBytes(StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface KeyReader {
        Object read(int index) throws Exception;
    }
}
