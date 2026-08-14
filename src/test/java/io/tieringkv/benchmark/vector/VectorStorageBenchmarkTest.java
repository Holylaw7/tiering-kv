package io.tieringkv.benchmark.vector;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.indexfile.VectorIndexFile;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import io.tieringkv.vector.io.VectorIndexMmapReader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v4 M1 向量存储基准（ADR-0319）：checkpoint 写入、mmap 读取 P50/P99。
 */
@Tag("benchmark")
class VectorStorageBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void checkpointAndMmapReadLatency() throws Exception {
        int count = 20_000;
        int dim = 64;
        VectorIndexStore store = new VectorIndexStore(6);
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = ((i + d) % 100) / 100.0f;
            }
            store.put(new Embedding("bench-" + i, values));
        }
        Path file = dir.resolve("vec-bench.tvif");

        long t0 = System.nanoTime();
        store.checkpoint(file);
        double writeSeconds = (System.nanoTime() - t0)
                / 1_000_000_000.0;
        double writeOps = count / writeSeconds;

        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(4096), pool);
        long[] samples = new long[Math.min(count, 5_000)];
        int idx = 0;
        try (VectorIndexMmapReader reader =
                     new VectorIndexMmapReader(file, 1, cache)) {
            List<Embedding> all = reader.readAll(); // 预热 + 缓存填充
            assertThat(all).hasSize(count);
            float[] query = new float[dim];
            for (int i = 0; i < dim; i++) {
                query[i] = i / 100.0f;
            }
            VectorStore storeCopy = new VectorStore();
            for (Embedding e : all) {
                storeCopy.put(e);
            }
            for (int i = 0; i < 2_000; i++) {
                long s = System.nanoTime();
                storeCopy.search(query, 10);
                if (idx < samples.length) {
                    samples[idx++] = System.nanoTime() - s;
                }
            }
        } finally {
            pool.close();
        }
        Arrays.sort(samples, 0, idx);
        double p50 = samples[idx / 2] / 1_000_000.0;
        double p99 = samples[(int) (idx * 0.99)] / 1_000_000.0;
        System.out.printf(Locale.ROOT,
                "PHASE58-BENCH VECTOR checkpoint entries=%d "
                        + "writeOps/s=%.0f mmapSearchP50=%.3fms "
                        + "mmapSearchP99=%.3fms%n",
                count, writeOps, p50, p99);
    }
}
