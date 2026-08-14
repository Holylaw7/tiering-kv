package io.tieringkv.benchmark.io;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexFile;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import io.tieringkv.vector.io.VectorIndexMmapReader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冷/热性能基线（ADR-0322，TD-009）：空 BlockCache 首次 mmap 全量读取
 * vs 预热后二次读取（进程内口径；OS 页缓存口径由脚本 drop caches）。
 */
@Tag("benchmark")
class ColdCacheBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void coldVsHotMmapRead() throws Exception {
        int count = 20_000;
        int dim = 64;
        VectorIndexStore store = new VectorIndexStore(6);
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = ((i + d) % 100) / 100.0f;
            }
            store.put(new Embedding("b-" + i, values));
        }
        Path file = dir.resolve("cold-hot.tvif");
        store.checkpoint(file);
        assertThat(VectorIndexFile.read(file).embeddings())
                .hasSize(count);

        MemoryPool pool = new MemoryPool();
        try {
            // 冷：容量 1 的缓存 ≈ 无缓存，首次读取
            BlockCache coldCache = new BlockCache(
                    new CachePolicy(1), pool);
            long coldStart = System.nanoTime();
            List<Embedding> coldAll;
            try (VectorIndexMmapReader reader =
                         new VectorIndexMmapReader(file, 1,
                                 coldCache)) {
                coldAll = reader.readAll();
            }
            double coldMs = (System.nanoTime() - coldStart)
                    / 1_000_000.0;
            assertThat(coldAll).hasSize(count);

            // 热：大缓存预热后二次读取
            BlockCache hotCache = new BlockCache(
                    new CachePolicy(4096), pool);
            try (VectorIndexMmapReader reader =
                         new VectorIndexMmapReader(file, 2,
                                 hotCache)) {
                reader.readAll();
                long hotStart = System.nanoTime();
                List<Embedding> hotAll = reader.readAll();
                double hotMs = (System.nanoTime() - hotStart)
                        / 1_000_000.0;
                assertThat(hotAll).hasSize(count);
                System.out.printf(Locale.ROOT,
                        "PHASE61-BENCH COLD-HOT entries=%d "
                                + "coldMs=%.3f hotMs=%.3f "
                                + "speedup=%.1fx%n",
                        count, coldMs, hotMs,
                        coldMs / Math.max(hotMs, 0.001));
            }
        } finally {
            pool.close();
        }
    }
}
