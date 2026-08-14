package io.tieringkv.vector;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.sql.SqlIndexRegistry;
import io.tieringkv.vector.indexfile.VectorIndexFile;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import io.tieringkv.vector.io.VectorIndexMmapReader;
import io.tieringkv.vector.sql.VectorSqlSearch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v4 M1 全链路 E2E（ADR-0319）：put → checkpoint → load → mmap 读取
 * → SQL 混合检索。
 */
class VectorStorageE2ETest {

    @TempDir
    Path dir;

    @Test
    void fullCrudPersistenceAndHybridSearch() throws Exception {
        // 1. 写入 + checkpoint
        VectorIndexStore store = new VectorIndexStore(4);
        for (int i = 0; i < 50; i++) {
            float[] values = new float[4];
            values[0] = (i % 5) / 4.0f;
            values[1] = (4 - i % 5) / 4.0f;
            values[2] = 0.1f * i;
            values[3] = 0.5f;
            store.put(new Embedding("doc-" + i, values));
        }
        store.delete("doc-0");
        Path file = dir.resolve("vector-index.tvif");
        store.checkpoint(file);
        assertThat(VectorIndexFile.read(file).embeddings())
                .hasSize(49);

        // 2. 加载重建 + mmap 读取
        VectorIndexStore loaded = VectorIndexStore.load(file);
        assertThat(loaded.size()).isEqualTo(49);
        assertThat(loaded.rebuildIndex().size()).isEqualTo(49);

        MemoryPool pool = new MemoryPool();
        try (VectorIndexMmapReader reader =
                     new VectorIndexMmapReader(file, 3,
                             new BlockCache(new CachePolicy(1024),
                                     pool))) {
            assertThat(reader.readAll()).hasSize(49);
        } finally {
            pool.close();
        }

        // 3. SQL 混合检索：向量 top-K + 标量谓词（id 前缀）
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.registerVector("docs", "embedding", 49, 4);
        VectorSqlSearch search = new VectorSqlSearch(registry);
        List<VectorStore.ScoredEmbedding> results = search.search(
                loaded.store(), "docs", "embedding",
                new float[]{1, 0, 0.5f, 0.5f}, 3,
                id -> id.endsWith("1") || id.endsWith("3"));
        assertThat(results).isNotEmpty();
        assertThat(results).allMatch(r ->
                r.id().endsWith("1") || r.id().endsWith("3"));
        assertThat(results.size()).isLessThanOrEqualTo(3);
    }
}
