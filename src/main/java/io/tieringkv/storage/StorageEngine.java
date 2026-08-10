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

    boolean exists(byte[] key);

    StorageIterator iterator();

    /** 存活 entry 数量（不含 tombstone；过期但未清扫的键仍计数，与 Redis 语义一致）。 */
    long size();

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
