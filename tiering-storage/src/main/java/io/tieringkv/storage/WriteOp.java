package io.tieringkv.storage;

/**
 * 同分片零拷贝批量写操作描述（v6.5.0 Phase 2）：
 * 用于 ShardWorker 连续写批聚合，不克隆 key/value 字节数组，减少热路径堆分配。
 */
public record WriteOp(boolean delete, byte[] key, byte[] value, long ttlMillis) {

    public static WriteOp put(byte[] key, byte[] value, long ttlMillis) {
        return new WriteOp(false, key, value, ttlMillis);
    }

    public static WriteOp put(byte[] key, byte[] value) {
        return new WriteOp(false, key, value, StorageEngine.NO_TTL);
    }

    public static WriteOp delete(byte[] key) {
        return new WriteOp(true, key, null, StorageEngine.NO_TTL);
    }
}
