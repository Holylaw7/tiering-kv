package io.tieringkv.vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 向量存储（ADR-0113）：暴力余弦检索原型。 */
public final class VectorStore {

    private final Map<String, Embedding> embeddings =
            new ConcurrentHashMap<>();

    public void put(Embedding embedding) {
        embeddings.put(embedding.id(), embedding);
    }

    public boolean delete(String id) {
        return embeddings.remove(id) != null;
    }

    public int size() {
        return embeddings.size();
    }

    public List<ScoredEmbedding> search(float[] query, int topK) {
        List<ScoredEmbedding> results = new ArrayList<>();
        for (Embedding embedding : embeddings.values()) {
            float[] values = embedding.values();
            if (query.length == 0 || values.length == 0
                    || isZero(values)) {
                continue; // 空/全零向量不参与检索
            }
            results.add(new ScoredEmbedding(embedding.id(),
                    cosine(query, values)));
        }
        results.sort(Comparator.comparingDouble(
                ScoredEmbedding::score).reversed());
        return results.size() > topK
                ? List.copyOf(results.subList(0, topK)) : results;
    }

    private static boolean isZero(float[] values) {
        for (float value : values) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length || a.length == 0) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    public record ScoredEmbedding(String id, double score) {
    }
}
