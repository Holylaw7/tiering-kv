package io.tieringkv.vector;

import java.util.List;
import java.util.function.Predicate;

/** 混合检索（ADR-0117）：向量 topK + 标量谓词过滤。 */
public final class HybridSearch {

    public List<VectorStore.ScoredEmbedding> search(VectorStore store,
                                                    float[] query,
                                                    int topK,
                                                    Predicate<String>
                                                            idFilter) {
        return store.search(query, Integer.MAX_VALUE).stream()
                .filter(result -> idFilter.test(result.id()))
                .limit(topK)
                .toList();
    }
}
