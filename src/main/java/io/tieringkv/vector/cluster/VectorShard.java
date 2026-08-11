package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;

/** 向量分片（ADR-0121）：独立 VectorStore。 */
public final class VectorShard {

    private final int shardId;
    private final VectorStore store = new VectorStore();

    public VectorShard(int shardId) {
        this.shardId = shardId;
    }

    public int shardId() {
        return shardId;
    }

    public void put(Embedding embedding) {
        store.put(embedding);
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    public int size() {
        return store.size();
    }

    public VectorStore store() {
        return store;
    }
}
