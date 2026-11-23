package io.tieringkv.vector.cluster;

import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;

/** 向量分片迁移执行（ADR-0127）：逐 id 迁移 + 校验。 */
public final class ShardMigrationExecutor {

    private final VectorStore source;
    private final VectorStore target;

    public ShardMigrationExecutor(VectorStore source,
                                  VectorStore target) {
        this.source = source;
        this.target = target;
    }

    public int migrate(String id, Embedding embedding) {
        target.put(embedding);
        source.delete(id);
        return 1;
    }

    public boolean verify() {
        return source.size() == 0;
    }
}
