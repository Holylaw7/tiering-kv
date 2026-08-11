package io.tieringkv.vector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量存储原型（ADR-0113）：余弦检索与 topK。 */
class VectorStoreTest {

    @Test
    void putAndSearchExact() {
        VectorStore store = new VectorStore();
        store.put(new Embedding("a", new float[]{1, 0}));
        store.put(new Embedding("b", new float[]{0, 1}));
        List<VectorStore.ScoredEmbedding> results =
                store.search(new float[]{1, 0}, 2);
        assertThat(results.get(0).id()).isEqualTo("a");
        assertThat(results.get(0).score()).isGreaterThan(
                results.get(1).score());
    }

    @Test
    void topKRespected() {
        VectorStore store = new VectorStore();
        for (int i = 0; i < 10; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i, 10 - i}));
        }
        assertThat(store.search(new float[]{10, 0}, 3)).hasSize(3);
    }

    @Test
    void emptyStoreSearchEmpty() {
        VectorStore store = new VectorStore();
        assertThat(store.search(new float[]{1, 1}, 5)).isEmpty();
    }

    @Test
    void deleteRemoves() {
        VectorStore store = new VectorStore();
        store.put(new Embedding("a", new float[]{1, 0}));
        assertThat(store.delete("a")).isTrue();
        assertThat(store.delete("a")).isFalse();
        assertThat(store.size()).isZero();
    }

    @Test
    void cosineIdenticalIsOne() {
        float[] vector = {1, 2, 3};
        assertThat(VectorStore.cosine(vector, vector)).isEqualTo(1.0);
    }

    @Test
    void cosineOrthogonalIsZero() {
        assertThat(VectorStore.cosine(new float[]{1, 0},
                new float[]{0, 1})).isEqualTo(0.0);
    }

    @Test
    void cosineZeroVectorIsZero() {
        assertThat(VectorStore.cosine(new float[]{0, 0},
                new float[]{1, 1})).isEqualTo(0.0);
    }

    @Test
    void cosineDimensionMismatchZero() {
        assertThat(VectorStore.cosine(new float[]{1},
                new float[]{1, 1})).isEqualTo(0.0);
    }

    @ParameterizedTest(name = "dim {0}")
    @ValueSource(ints = {2, 8, 64})
    void parameterizedDimensions(int dim) {
        VectorStore store = new VectorStore();
        float[] query = new float[dim];
        query[0] = 1;
        store.put(new Embedding("q", query));
        store.put(new Embedding("other", new float[dim]));
        List<VectorStore.ScoredEmbedding> results =
                store.search(query, 1);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).id()).isEqualTo("q");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 100, 1000})
    void parameterizedStoreSize(int count) {
        VectorStore store = new VectorStore();
        for (int i = 0; i < count; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 7, 7 - i % 7}));
        }
        assertThat(store.size()).isEqualTo(count);
        assertThat(store.search(new float[]{1, 1}, 5))
                .hasSize(Math.min(5, count));
    }

    @Test
    void overwriteSameId() {
        VectorStore store = new VectorStore();
        store.put(new Embedding("a", new float[]{1, 0}));
        store.put(new Embedding("a", new float[]{0, 1}));
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.search(new float[]{0, 1}, 1).get(0).id())
                .isEqualTo("a");
    }
}
