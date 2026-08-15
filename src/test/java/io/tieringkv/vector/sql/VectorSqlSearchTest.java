package io.tieringkv.vector.sql;

import io.tieringkv.sql.SqlIndexRegistry;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SQL 向量混合检索（ADR-0319）：索引校验 + 维度校验 + 谓词过滤。 */
class VectorSqlSearchTest {

    private static VectorStore storeWith(String... ids) {
        VectorStore store = new VectorStore();
        int i = 0;
        for (String id : ids) {
            float[] values = new float[2];
            values[0] = (i % 3 == 0) ? 1 : 0;
            values[1] = (i % 3 == 0) ? 0 : 1;
            store.put(new Embedding(id, values));
            i++;
        }
        return store;
    }

    private static SqlIndexRegistry registryWithVector() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.registerVector("docs", "embedding", 10, 2);
        return registry;
    }

    @Test
    void searchRequiresVectorIndex() {
        SqlIndexRegistry registry = new SqlIndexRegistry();
        registry.register("docs", "title", false, 10);
        VectorSqlSearch search = new VectorSqlSearch(registry);
        assertThatThrownBy(() -> search.search(
                new VectorStore(), "docs", "embedding",
                new float[]{1, 0}, 5, id -> true))
                .hasMessageContaining("vector index required");
    }

    @Test
    void queryDimensionMustMatchIndex() {
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        assertThatThrownBy(() -> search.search(
                new VectorStore(), "docs", "embedding",
                new float[]{1, 0, 0}, 5, id -> true))
                .hasMessageContaining("query dim");
    }

    @Test
    void scalarPredicateFiltersCandidates() {
        VectorStore store = storeWith("hot", "cold-1", "cold-2");
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        List<VectorStore.ScoredEmbedding> results = search.search(
                store, "docs", "embedding", new float[]{1, 0}, 5,
                id -> id.startsWith("cold"));
        assertThat(results).extracting(
                VectorStore.ScoredEmbedding::id)
                .containsExactly("cold-1", "cold-2");
    }

    @ParameterizedTest(name = "topK {0}")
    @ValueSource(ints = {1, 3, 100})
    void topKRespectedAfterFilter(int topK) {
        VectorStore store = storeWith("a", "b", "c", "d");
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        List<VectorStore.ScoredEmbedding> results = search.search(
                store, "docs", "embedding", new float[]{1, 0}, topK,
                id -> true);
        assertThat(results).hasSize(Math.min(topK, 4));
    }

    @Test
    void noMatchReturnsEmpty() {
        VectorStore store = storeWith("a", "b");
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        assertThat(search.search(store, "docs", "embedding",
                new float[]{1, 0}, 5, id -> id.equals("nope")))
                .isEmpty();
    }

    @Test
    void collectionAwareSearchResolvesStore() {
        VectorCollectionRegistry collections =
                new VectorCollectionRegistry();
        collections.put("docs", new Embedding("hot",
                new float[]{1, 0}));
        collections.put("docs", new Embedding("cold-1",
                new float[]{0, 1}));
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        search.bindCollection("docs", "embedding", "docs");
        List<VectorStore.ScoredEmbedding> results = search.search(
                collections, "docs", "embedding", new float[]{1, 0},
                5, id -> id.startsWith("cold"));
        assertThat(results).extracting(
                VectorStore.ScoredEmbedding::id)
                .containsExactly("cold-1");
    }

    @Test
    void collectionAwareSearchRequiresBinding() {
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        assertThatThrownBy(() -> search.search(
                new VectorCollectionRegistry(), "docs", "embedding",
                new float[]{1, 0}, 5, id -> true))
                .hasMessageContaining("no vector collection bound");
    }

    @Test
    void collectionAwareSearchMissingCollectionRejected() {
        VectorSqlSearch search = new VectorSqlSearch(
                registryWithVector());
        search.bindCollection("docs", "embedding", "missing");
        assertThatThrownBy(() -> search.search(
                new VectorCollectionRegistry(), "docs", "embedding",
                new float[]{1, 0}, 5, id -> true))
                .hasMessageContaining("collection not found");
    }
}
