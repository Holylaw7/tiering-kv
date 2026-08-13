package io.tieringkv.vector;

import io.tieringkv.vector.hnsw.HnswIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** HNSW 持久化（ADR-0295）。 */
class VectorPersistenceTest {

    private static List<Embedding> embeddings(int count,
                                              int dim) {
        List<Embedding> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = (i + d) * 0.1f;
            }
            list.add(new Embedding("id-" + i, values));
        }
        return list;
    }

    @Test
    void serializeDeserializeRoundTrip() throws Exception {
        HnswIndex index = new HnswIndex(3);
        index.build(embeddings(10, 4));
        byte[] bytes = index.serialize();
        HnswIndex restored = HnswIndex.deserialize(bytes);
        assertThat(restored.size()).isEqualTo(index.size());
    }

    @Test
    void restoredSearchMatchesOriginal() throws Exception {
        HnswIndex index = new HnswIndex(4);
        index.build(embeddings(20, 8));
        float[] query = new float[]{0.0f, 0.1f, 0.2f, 0.3f,
                0.4f, 0.5f, 0.6f, 0.7f};
        List<VectorStore.ScoredEmbedding> before =
                index.search(query, 5);
        HnswIndex restored = HnswIndex.deserialize(
                index.serialize());
        List<VectorStore.ScoredEmbedding> after =
                restored.search(query, 5);
        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i).id())
                    .isEqualTo(before.get(i).id());
        }
    }

    @Test
    void emptyIndexRoundTrip() throws Exception {
        HnswIndex index = new HnswIndex(2);
        HnswIndex restored = HnswIndex.deserialize(
                index.serialize());
        assertThat(restored.size()).isZero();
    }

    @Test
    void singleEmbeddingRoundTrip() throws Exception {
        HnswIndex index = new HnswIndex(1);
        index.build(embeddings(1, 2));
        HnswIndex restored = HnswIndex.deserialize(
                index.serialize());
        assertThat(restored.size()).isEqualTo(1);
    }

    @Test
    void highDimensionalRoundTrip() throws Exception {
        HnswIndex index = new HnswIndex(5);
        index.build(embeddings(5, 128));
        HnswIndex restored = HnswIndex.deserialize(
                index.serialize());
        assertThat(restored.size()).isEqualTo(5);
    }

    @ParameterizedTest(name = "index {0}")
    @MethodSource("indexMatrix")
    void matrixRoundTrip(int maxLevel, int count, int dim)
            throws Exception {
        HnswIndex index = new HnswIndex(maxLevel);
        index.build(embeddings(count, dim));
        HnswIndex restored = HnswIndex.deserialize(
                index.serialize());
        assertThat(restored.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "query {0}")
    @MethodSource("queryMatrix")
    void searchAfterRestoreConsistent(int count, int dim,
                                      int topK)
            throws Exception {
        HnswIndex index = new HnswIndex(3);
        index.build(embeddings(count, dim));
        float[] query = new float[dim];
        for (int d = 0; d < dim; d++) {
            query[d] = d * 0.05f;
        }
        HnswIndex restored = HnswIndex.deserialize(
                index.serialize());
        assertThat(restored.search(query, topK))
                .hasSize(Math.min(topK, count));
    }

    static Stream<Arguments> indexMatrix() {
        return Stream.of(
                Arguments.of(1, 3, 2),
                Arguments.of(2, 5, 4),
                Arguments.of(3, 8, 8),
                Arguments.of(4, 12, 16),
                Arguments.of(5, 20, 32));
    }

    static Stream<Arguments> queryMatrix() {
        return Stream.of(
                Arguments.of(5, 4, 2),
                Arguments.of(10, 8, 3),
                Arguments.of(15, 16, 5),
                Arguments.of(20, 32, 10),
                Arguments.of(25, 64, 20));
    }
}
