package io.tieringkv.vector.io;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量索引 mmap 读取（ADR-0319）：与内存检索一致 + BlockCache。 */
class VectorIndexMmapReaderTest {

    @TempDir
    Path dir;

    private static VectorIndexFile.IndexData data(int count, int dim) {
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = (i + d) * 0.1f;
            }
            embeddings.add(new Embedding("mmap-" + i, values));
        }
        return new VectorIndexFile.IndexData(4, dim, embeddings);
    }

    @Test
    void mmapReadMatchesEncodedData() throws Exception {
        Path file = dir.resolve("vec.tvif");
        VectorIndexFile.IndexData source = data(100, 8);
        VectorIndexFile.write(file, source);
        MemoryPool pool = new MemoryPool();
        try (VectorIndexMmapReader reader =
                     new VectorIndexMmapReader(file, 1,
                             new BlockCache(new CachePolicy(256), pool))) {
            assertThat(reader.maxLevel()).isEqualTo(4);
            assertThat(reader.dim()).isEqualTo(8);
            assertThat(reader.entryCount()).isEqualTo(100);
            List<Embedding> read = reader.readAll();
            assertThat(read).hasSize(100);
            assertThat(read.get(0).id()).isEqualTo("mmap-0");
            assertThat(read.get(99).values()[7])
                    .isEqualTo(106 * 0.1f);
        } finally {
            pool.close();
        }
    }

    @Test
    void blockCacheHitsOnSecondRead() throws Exception {
        Path file = dir.resolve("vec.tvif");
        VectorIndexFile.write(file, data(20, 4));
        MemoryPool pool = new MemoryPool();
        BlockCache cache = new BlockCache(new CachePolicy(512), pool);
        try (VectorIndexMmapReader reader =
                     new VectorIndexMmapReader(file, 7, cache)) {
            reader.readAll();
            long missesAfterFirst =
                    cache.statistics().snapshot().misses();
            reader.readAll();
            assertThat(cache.statistics().snapshot().hits())
                    .isPositive();
            assertThat(cache.statistics().snapshot().misses())
                    .isEqualTo(missesAfterFirst);
        } finally {
            pool.close();
        }
    }

    @Test
    void corruptedHeaderRejected() throws Exception {
        Path file = dir.resolve("bad.tvif");
        VectorIndexFile.write(file, data(5, 4));
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        bytes[0] = 'X';
        java.nio.file.Files.write(file, bytes);
        MemoryPool pool = new MemoryPool();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    Exception.class,
                    () -> new VectorIndexMmapReader(file, 1,
                            new BlockCache(new CachePolicy(8), pool)));
        } finally {
            pool.close();
        }
    }
}
