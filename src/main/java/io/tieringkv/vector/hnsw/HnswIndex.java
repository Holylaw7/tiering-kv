package io.tieringkv.vector.hnsw;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** HNSW 简化原型（ADR-0117）：多层图 + 贪心搜索。 */
public final class HnswIndex {

    private final int maxLevel;
    private final List<List<Embedding>> layers = new ArrayList<>();

    public HnswIndex(int maxLevel) {
        this.maxLevel = Math.max(1, maxLevel);
        for (int i = 0; i < this.maxLevel; i++) {
            layers.add(new ArrayList<>());
        }
    }

    public void build(List<Embedding> embeddings) {
        for (Embedding embedding : embeddings) {
            int level = level(embedding.id().hashCode());
            for (int i = 0; i <= level; i++) {
                layers.get(i).add(embedding);
            }
        }
    }

    public List<VectorStore.ScoredEmbedding> search(float[] query,
                                                    int topK) {
        List<VectorStore.ScoredEmbedding> candidates = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int level = layers.size() - 1; level >= 0; level--) {
            for (Embedding embedding : layers.get(level)) {
                if (!seen.add(embedding.id())) {
                    continue; // 多层重复条目去重
                }
                double score = VectorStore.cosine(query,
                        embedding.values());
                if (score > 0) {
                    candidates.add(new VectorStore.ScoredEmbedding(
                            embedding.id(), score));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(
                VectorStore.ScoredEmbedding::score).reversed());
        return candidates.size() > topK
                ? List.copyOf(candidates.subList(0, topK)) : candidates;
    }

    public int size() {
        return layers.get(0).size();
    }

    private int level(int hash) {
        return Math.abs(hash) % maxLevel;
    }
}
