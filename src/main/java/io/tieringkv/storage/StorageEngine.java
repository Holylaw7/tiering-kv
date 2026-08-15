package io.tieringkv.storage;

import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.Mutation;
import io.tieringkv.storage.memory.RawMutation;

import java.util.ArrayList;
import java.util.List;

/**
 * 存储引擎 SPI（ADR-0002 / ADR-0007）。
 * Command 层只依赖此接口，禁止依赖具体实现。
 */
public interface StorageEngine {

    /** 永不过期标记（内部 TTL 用毫秒）。 */
    long NO_TTL = -1L;

    void put(byte[] key, byte[] value);

    void put(byte[] key, byte[] value, long ttlMillis);

    byte[] get(byte[] key);

    boolean delete(byte[] key);

    /**
     * 物理移除（GC/回滚专用）：不产生 tombstone，立即回收内存。
     * 默认回退到 delete；MemTable 覆盖为段内物理删除。
     */
    default boolean removePhysical(byte[] key) {
        return delete(key);
    }

    /** 批量物理移除（ADR-0078）：默认逐条，MemTable 覆盖为分段单锁批量。 */
    default long removeAll(java.util.List<byte[]> keys) {
        long removed = 0;
        for (byte[] key : keys) {
            if (removePhysical(key)) {
                removed++;
            }
        }
        return removed;
    }

    /** 清空全部数据（FLUSHDB/FLUSHALL）：默认快照遍历 + 物理移除。 */
    default void clear() {
        java.util.List<byte[]> keys = new ArrayList<>();
        try (StorageIterator iterator = iterator()) {
            while (iterator.hasNext()) {
                keys.add(iterator.next().key());
            }
        }
        removeAll(keys);
    }

    boolean exists(byte[] key);

    StorageIterator iterator();

    /** 存活 entry 数量（不含 tombstone；过期但未清扫的键仍计数，与 Redis 语义一致）。 */
    long size();

    /** 键版本（ADR-0328，TD-018）：热缓存新鲜度校验；
     *  默认 0 = 无版本语义（缓存回退 TTL 兜底）。 */
    default long versionOf(byte[] key) {
        return 0;
    }

    /** 批量应用（ADR-0048）：默认逐条实现，MemTable 覆盖为分段单锁优化。 */
    default int applyBatch(BatchWriteRequest request) {
        for (Mutation mutation : request.mutations()) {
            if (mutation.type() == Mutation.Type.PUT) {
                put(mutation.key(), mutation.value(), mutation.ttlMillis());
            } else {
                delete(mutation.key());
            }
        }
        return request.mutations().size();
    }

    /**
     * 零拷贝批量应用（ADR-0059）：MemTable 覆盖为所有权转移实现；
     * 其他引擎默认回退到 applyBatch（拷贝路径，语义等价）。
     */
    default int applyRawBatch(List<RawMutation> mutations) {
        List<Mutation> converted = new ArrayList<>(mutations.size());
        for (RawMutation mutation : mutations) {
            converted.add(Mutation.put(
                    mutation.key(), mutation.value(), mutation.ttlMillis()));
        }
        return applyBatch(new BatchWriteRequest(converted));
    }
}
