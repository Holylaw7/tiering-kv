package io.tieringkv.storage;

import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.Mutation;
import io.tieringkv.storage.memory.RawMutation;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量索引同步装饰器（ADR-0320 M2 增强）：在存储层统一维护 VECTOR
 * 类型化值 → M1 VectorIndexStore 的索引生命周期。
 *
 * <p>put 写入 VECTOR 值 → 同步索引；delete 删除 VECTOR 值 → 同步移除；
 * 非 VECTOR 值不触碰索引。批量路径经 put/delete 委托自动同步。
 */
public final class VectorIndexSyncStorageEngine implements StorageEngine {

    private final StorageEngine delegate;
    private final VectorIndexStore vectorStore;

    public VectorIndexSyncStorageEngine(StorageEngine delegate,
                                        VectorIndexStore vectorStore) {
        if (delegate == null || vectorStore == null) {
            throw new IllegalArgumentException(
                    "delegate and vectorStore required");
        }
        this.delegate = delegate;
        this.vectorStore = vectorStore;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        delegate.put(key, value);
        indexIfVector(key, value);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        delegate.put(key, value, ttlMillis);
        indexIfVector(key, value);
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        byte[] value = delegate.get(key);
        boolean removed = delegate.delete(key);
        if (removed && value != null
                && TypedValueCodec.typeOf(value) == ValueType.VECTOR) {
            vectorStore.delete(new String(key,
                    StandardCharsets.UTF_8));
        }
        return removed;
    }

    @Override
    public boolean exists(byte[] key) {
        return delegate.exists(key);
    }

    @Override
    public StorageIterator iterator() {
        return delegate.iterator();
    }

    @Override
    public long size() {
        return delegate.size();
    }

    @Override
    public int applyBatch(BatchWriteRequest request) {
        for (Mutation mutation : request.mutations()) {
            if (mutation.type() == Mutation.Type.PUT) {
                put(mutation.key(), mutation.value(),
                        mutation.ttlMillis());
            } else {
                delete(mutation.key());
            }
        }
        return request.mutations().size();
    }

    @Override
    public int applyRawBatch(List<RawMutation> mutations) {
        List<Mutation> converted = new ArrayList<>(mutations.size());
        for (RawMutation mutation : mutations) {
            converted.add(Mutation.put(mutation.key(),
                    mutation.value(), mutation.ttlMillis()));
        }
        return applyBatch(new BatchWriteRequest(converted));
    }

    private void indexIfVector(byte[] key, byte[] value) {
        if (value != null
                && TypedValueCodec.typeOf(value) == ValueType.VECTOR) {
            vectorStore.put(new Embedding(
                    new String(key, StandardCharsets.UTF_8),
                    MultiModelCodec.decodeVector(value)));
        }
    }
}
