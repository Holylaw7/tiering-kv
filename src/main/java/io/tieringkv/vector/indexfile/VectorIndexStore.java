package io.tieringkv.vector.indexfile;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.hnsw.HnswIndex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 向量索引存储闭环（ADR-0319）：内存 VectorStore + 文件 checkpoint。
 *
 * <p>put/delete 走低延迟内存路径；{@link #checkpoint} 原子落盘
 * （temp + fsync + rename）；{@link #load} 校验 CRC 后重建；
 * {@link #rebuildIndex} 从唯一向量集重建 HNSW 分层。
 */
public final class VectorIndexStore {

    private final int maxLevel;
    private final VectorStore store = new VectorStore();
    private volatile int dim;

    public VectorIndexStore(int maxLevel) {
        this.maxLevel = Math.max(1, maxLevel);
    }

    public void put(Embedding embedding) {
        store.put(embedding);
        if (dim == 0 && embedding.values().length > 0) {
            dim = embedding.values().length;
        }
    }

    public boolean delete(String id) {
        return store.delete(id);
    }

    public int size() {
        return store.size();
    }

    public int dim() {
        return dim;
    }

    public VectorStore store() {
        return store;
    }

    public List<Embedding> snapshot() {
        return store.embeddings();
    }

    public void checkpoint(Path file) throws IOException {
        VectorIndexFile.write(file, new VectorIndexFile.IndexData(
                maxLevel, dim, snapshot()));
    }

    public static VectorIndexStore load(Path file) throws IOException {
        VectorIndexFile.IndexData data = VectorIndexFile.read(file);
        VectorIndexStore loaded = new VectorIndexStore(data.maxLevel());
        for (Embedding embedding : data.embeddings()) {
            loaded.put(embedding);
        }
        return loaded;
    }

    /** 从唯一向量集重建 HNSW 分层（确定性：hash(id) % maxLevel）。 */
    public HnswIndex rebuildIndex() {
        HnswIndex index = new HnswIndex(maxLevel);
        index.build(snapshot());
        return index;
    }
}
