package io.tieringkv.mvcc.gc;

/** MVCC 批量 GC 配置（ADR-0078）。 */
public record GcConfig(int batchSize, int workerCount, long maxMemoryBytes) {

    public static final GcConfig DEFAULT = new GcConfig(4096, 4, 64L << 20);

    public GcConfig {
        batchSize = Math.max(1, batchSize);
        workerCount = Math.max(1, workerCount);
        maxMemoryBytes = Math.max(1, maxMemoryBytes);
    }
}
