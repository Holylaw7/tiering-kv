package io.tieringkv.storage.memory;

/**
 * 零拷贝批量变更（ADR-0059）：不克隆 key/value，所有权随 applyRawBatch
 * 转移给 MemTable；调用方必须在转移后停止修改数组。
 * version 为源版本（迁移元数据），目标版本由 applyRawBatch 按顺序分配。
 */
public record RawMutation(byte[] key, byte[] value, long version, long ttlMillis) {

    public static RawMutation of(byte[] key, byte[] value, long version, long ttlMillis) {
        return new RawMutation(key, value, version, ttlMillis);
    }
}
