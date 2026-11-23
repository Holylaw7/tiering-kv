package io.tieringkv.vector.hnsw;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** HNSW 多层图（ADR-0332）：图结构、召回率、序列化、去重与边界。 */
class HnswGraphTest {

    private static List<Embedding> randomVectors(int count, int dim,
                                                 long seed) {
        Random random = new Random(seed);
        List<Embedding> embeddings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            float[] values = new float[dim];
            for (int d = 0; d < dim; d++) {
                values[d] = random.nextFloat() * 2 - 1;
            }
            embeddings.add(new Embedding("v" + i, values));
        }
        return embeddings;
    }

    @Test
    void graphHasEdgesAfterBuild() {
        HnswIndex index = new HnswIndex(4);
        index.build(randomVectors(200, 32, 1));
        assertThat(index.size()).isEqualTo(200);
        assertThat(index.edgeCount()).isPositive();
    }

    @Test
    void recallAgainstBruteForceAtLeastNinetyPercent() {
        int count = 2_000;
        int dim = 64;
        int topK = 10;
        HnswIndex index = new HnswIndex(6);
        List<Embedding> embeddings = randomVectors(count, dim, 7);
        index.build(embeddings);
        VectorStore brute = new VectorStore();
        embeddings.forEach(brute::put);

        Random random = new Random(99);
        int queries = 20;
        double totalRecall = 0;
        for (int q = 0; q < queries; q++) {
            float[] query = new float[dim];
            for (int d = 0; d < dim; d++) {
                query[d] = random.nextFloat() * 2 - 1;
            }
            Set<String> hnsw = index.search(query, topK).stream()
                    .map(VectorStore.ScoredEmbedding::id)
                    .collect(Collectors.toSet());
            Set<String> exact = brute.search(query, topK).stream()
                    .map(VectorStore.ScoredEmbedding::id)
                    .collect(Collectors.toSet());
            hnsw.retainAll(exact);
            totalRecall += hnsw.size() / (double) topK;
        }
        double recall = totalRecall / queries;
        assertThat(recall).as("HNSW recall@%d", topK)
                .isGreaterThanOrEqualTo(0.9);
    }

    @Test
    void serializationPreservesGraphAndSearch() throws Exception {
        HnswIndex index = new HnswIndex(5);
        index.build(randomVectors(600, 32, 3));
        byte[] bytes = index.serialize();
        HnswIndex restored = HnswIndex.deserialize(bytes);
        assertThat(restored.size()).isEqualTo(600);
        assertThat(restored.edgeCount()).isEqualTo(index.edgeCount());

        Random random = new Random(5);
        for (int q = 0; q < 5; q++) {
            float[] query = new float[32];
            for (int d = 0; d < 32; d++) {
                query[d] = random.nextFloat() * 2 - 1;
            }
            List<VectorStore.ScoredEmbedding> before =
                    index.search(query, 10);
            List<VectorStore.ScoredEmbedding> after =
                    restored.search(query, 10);
            assertThat(after).hasSameSizeAs(before);
            for (int i = 0; i < before.size(); i++) {
                assertThat(after.get(i).id())
                        .isEqualTo(before.get(i).id());
            }
        }
    }

    @Test
    void deterministicBuildSerializesIdentically() throws Exception {
        List<Embedding> embeddings = randomVectors(300, 16, 11);
        HnswIndex first = new HnswIndex(4);
        first.build(embeddings);
        HnswIndex second = new HnswIndex(4);
        second.build(embeddings);
        assertThat(second.serialize()).isEqualTo(first.serialize());
    }

    @Test
    void duplicateIdsAreDeduplicated() {
        HnswIndex index = new HnswIndex(3);
        index.build(List.of(
                new Embedding("dup", new float[]{1, 0}),
                new Embedding("dup", new float[]{0, 1}),
                new Embedding("other", new float[]{0, 1})));
        assertThat(index.size()).isEqualTo(2);
        List<VectorStore.ScoredEmbedding> results =
                index.search(new float[]{1, 0}, 5);
        Set<String> ids = new HashSet<>();
        results.forEach(result -> ids.add(result.id()));
        assertThat(ids).containsExactlyInAnyOrder("dup", "other");
    }

    @Test
    void zeroVectorExcludedFromResults() {
        HnswIndex index = new HnswIndex(3);
        index.build(List.of(
                new Embedding("zero", new float[]{0, 0}),
                new Embedding("near", new float[]{1, 0}),
                new Embedding("far", new float[]{0, 1})));
        assertThat(index.size()).isEqualTo(3);
        List<VectorStore.ScoredEmbedding> results =
                index.search(new float[]{1, 0}, 5);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).id()).isEqualTo("near");
        assertThat(results)
                .extracting(VectorStore.ScoredEmbedding::id)
                .doesNotContain("zero");
    }

    @Test
    void emptyIndexSearchAndRoundTrip() throws Exception {
        HnswIndex index = new HnswIndex(3);
        assertThat(index.search(new float[]{1, 0}, 5)).isEmpty();
        HnswIndex restored = HnswIndex.deserialize(index.serialize());
        assertThat(restored.size()).isZero();
        assertThat(restored.edgeCount()).isZero();
    }

    @Test
    void topKLargerThanSizeClamped() {
        HnswIndex index = new HnswIndex(3);
        index.build(randomVectors(10, 8, 4));
        assertThat(index.search(new float[]{1, 0}, 100)).hasSize(10);
    }
}
