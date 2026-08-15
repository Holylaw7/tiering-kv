package io.tieringkv.observability;

import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * 向量指标（ADR-0344）：引用 VectorIndexStore 读取当前状态 +
 * 写/删计数（由 VectorIndexSyncStorageEngine 喂入）。
 */
public final class VectorMetricsRegistry {

    private final VectorIndexStore store;
    private final LongAdder writes = new LongAdder();
    private final LongAdder deletes = new LongAdder();

    public VectorMetricsRegistry() {
        this(null);
    }

    public VectorMetricsRegistry(VectorIndexStore store) {
        this.store = store;
    }

    public void recordVectorWrite() {
        writes.increment();
    }

    public void recordVectorDelete() {
        deletes.increment();
    }

    public Snapshot snapshot() {
        return new Snapshot(
                store == null ? 0 : store.size(),
                store == null ? 0 : store.dim(),
                store == null ? 0 : store.maxLevel(),
                writes.sum(),
                deletes.sum());
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "vector_count:%d\r\n"
                        + "vector_dim:%d\r\n"
                        + "vector_max_level:%d\r\n"
                        + "vector_writes:%d\r\n"
                        + "vector_deletes:%d\r\n",
                s.vectorCount(), s.dim(), s.maxLevel(),
                s.writes(), s.deletes());
    }

    public record Snapshot(long vectorCount, int dim, int maxLevel,
                           long writes, long deletes) {
    }
}
