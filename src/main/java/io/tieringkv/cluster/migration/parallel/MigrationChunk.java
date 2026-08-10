package io.tieringkv.cluster.migration.parallel;

/**
 * 迁移分片（ADR-0063）：MemTable 按段范围分片；通用存储按键范围
 * （fallback 单分片）。checksum 由 worker 逐条目累积。
 */
public record MigrationChunk(
        int chunkId,
        int segmentFrom,
        int segmentTo,
        byte[] startKey,
        byte[] endKey,
        long version) {

    public MigrationChunk {
        startKey = startKey == null ? new byte[0] : startKey.clone();
        endKey = endKey == null ? null : endKey.clone();
    }

    @Override
    public byte[] startKey() {
        return startKey.clone();
    }

    @Override
    public byte[] endKey() {
        return endKey == null ? null : endKey.clone();
    }

    public boolean covers(byte[] key) {
        if (java.util.Arrays.compareUnsigned(key, startKey) < 0) {
            return false;
        }
        return endKey == null || java.util.Arrays.compareUnsigned(key, endKey) < 0;
    }
}
