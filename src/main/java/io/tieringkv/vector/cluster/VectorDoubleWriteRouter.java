package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 向量双写路由（ADR-0134）：迁移窗口双写 + 原子切换。 */
public final class VectorDoubleWriteRouter {

    private final VectorStore primary;
    private final VectorStore secondary;
    private boolean migrating;

    public VectorDoubleWriteRouter(VectorStore primary,
                                   VectorStore secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    public void beginMigration() {
        migrating = true;
    }

    public void put(Embedding embedding) {
        primary.put(embedding);
        if (migrating) {
            secondary.put(embedding);
        }
    }

    public boolean delete(String id) {
        boolean removed = primary.delete(id);
        if (migrating) {
            secondary.delete(id);
        }
        return removed;
    }

    public void commitSwitch() {
        migrating = false;
    }

    public void rollback() {
        migrating = false;
        secondary.clear();
    }

    public List<VectorStore.ScoredEmbedding> search(float[] query,
                                                    int topK) {
        List<VectorStore.ScoredEmbedding> candidates =
                new ArrayList<>(primary.search(query,
                        Math.max(1, topK)));
        if (migrating) {
            candidates.addAll(secondary.search(query,
                    Math.max(1, topK)));
        }
        candidates.sort(Comparator.comparingDouble(
                VectorStore.ScoredEmbedding::score).reversed());
        return candidates.size() > topK
                ? List.copyOf(candidates.subList(0, topK))
                : candidates;
    }

    public boolean migrating() {
        return migrating;
    }

    public int primarySize() {
        return primary.size();
    }

    public int secondarySize() {
        return secondary.size();
    }
}
