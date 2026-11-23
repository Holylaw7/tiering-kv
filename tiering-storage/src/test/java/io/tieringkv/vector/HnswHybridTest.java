package io.tieringkv.vector;

import io.tieringkv.vector.hnsw.HnswIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** HNSW 与混合检索（ADR-0117）：构建、搜索、标量过滤。 */
class HnswHybridTest {

    @Test
    void buildAndSearchFindsNearest() {
        HnswIndex index = new HnswIndex(3);
        List<Embedding> embeddings = List.of(
                new Embedding("near", new float[]{1, 0}),
                new Embedding("far", new float[]{0, 1}));
        index.build(embeddings);
        assertThat(index.size()).isEqualTo(2);
        assertThat(index.search(new float[]{1, 0}, 1).get(0).id())
                .isEqualTo("near");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {10, 100, 500})
    void parameterizedBuildSearch(int count) {
        HnswIndex index = new HnswIndex(4);
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            embeddings.add(new Embedding("e" + i,
                    new float[]{i % 9, 9 - i % 9}));
        }
        index.build(embeddings);
        assertThat(index.size()).isEqualTo(count);
        assertThat(index.search(new float[]{1, 1}, 5)).hasSize(5);
    }

    @Test
    void emptyIndexSearchEmpty() {
        HnswIndex index = new HnswIndex(3);
        assertThat(index.search(new float[]{1, 1}, 5)).isEmpty();
    }

    @Test
    void hybridSearchFiltersByPredicate() {
        VectorStore store = new VectorStore();
        store.put(new Embedding("user:1", new float[]{1, 0}));
        store.put(new Embedding("order:1", new float[]{1, 0}));
        HybridSearch hybrid = new HybridSearch();
        List<VectorStore.ScoredEmbedding> results = hybrid.search(store,
                new float[]{1, 0}, 5, id -> id.startsWith("user"));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("user:1");
    }

    @Test
    void hybridSearchRespectsTopK() {
        VectorStore store = new VectorStore();
        for (int i = 0; i < 10; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 3 + 1, 3 - i % 3}));
        }
        HybridSearch hybrid = new HybridSearch();
        assertThat(hybrid.search(store, new float[]{1, 1}, 3,
                id -> true)).hasSize(3);
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedLevels(int levels) {
        HnswIndex index = new HnswIndex(levels);
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            embeddings.add(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        index.build(embeddings);
        assertThat(index.search(new float[]{1, 0}, 3)).hasSize(3);
    }

    @Test
    void hnswRecallOnSmallSet() {
        VectorStore brute = new VectorStore();
        HnswIndex index = new HnswIndex(3);
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Embedding embedding = new Embedding("e" + i,
                    new float[]{i % 7, 7 - i % 7});
            embeddings.add(embedding);
            brute.put(embedding);
        }
        index.build(embeddings);
        float[] query = {1, 1};
        double hnswScore = index.search(query, 1).get(0).score();
        double bruteScore = brute.search(query, 1).get(0).score();
        // 同分平局时节点顺序可能不同；断言分数相等（召回等价）。
        assertThat(hnswScore).isEqualTo(bruteScore);
    }

    @Test
    void hybridEmptyStoreEmpty() {
        HybridSearch hybrid = new HybridSearch();
        assertThat(hybrid.search(new VectorStore(), new float[]{1, 1},
                5, id -> true)).isEmpty();
    }
}
