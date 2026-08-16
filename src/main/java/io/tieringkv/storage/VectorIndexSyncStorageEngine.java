package io.tieringkv.storage;

import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

/**
 * 向量索引同步装饰器（ADR-0320 M2 增强）：在存储层统一维护 VECTOR
 * 类型化值 → M1 VectorIndexStore 的索引生命周期。
 *
 * <p>put 写入 VECTOR 值 → 同步索引；delete 删除 VECTOR 值 → 同步移除；
 * 非 VECTOR 值不触碰索引。批量路径经 put/delete 委托自动同步；
 * 原子写（GETSET/GETDEL/SETNX/UPDATE）同样维护索引（ADR-0351）。
 */
public final class VectorIndexSyncStorageEngine extends AbstractStorageDecorator {

    private final VectorIndexStore vectorStore;
    private final VectorMetricsRegistry metrics;

    public VectorIndexSyncStorageEngine(StorageEngine delegate,
                                        VectorIndexStore vectorStore) {
        this(delegate, vectorStore, null);
    }

    /** 可观测性收口（ADR-0344）：可选向量指标注册表（additive）。 */
    public VectorIndexSyncStorageEngine(StorageEngine delegate,
                                        VectorIndexStore vectorStore,
                                        VectorMetricsRegistry metrics) {
        super(delegate);
        if (vectorStore == null) {
            throw new IllegalArgumentException(
                    "vectorStore required");
        }
        this.vectorStore = vectorStore;
        this.metrics = metrics;
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
            if (metrics != null) {
                metrics.recordVectorDelete();
            }
        }
        return removed;
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        byte[] old = atomic().getSet(key, value);
        syncValueChange(key, old, value);
        return old;
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        byte[] old = atomic().getAndSetPreservingTtl(key, value);
        syncValueChange(key, old, value);
        return old;
    }

    @Override
    public byte[] getDelete(byte[] key) {
        byte[] old = atomic().getDelete(key);
        if (isVector(old)) {
            vectorStore.delete(new String(key, StandardCharsets.UTF_8));
            if (metrics != null) {
                metrics.recordVectorDelete();
            }
        }
        return old;
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        boolean wrote = atomic().putIfAbsent(key, value);
        if (wrote) {
            indexIfVector(key, value);
        }
        return wrote;
    }

    @Override
    public byte[] update(byte[] key, UnaryOperator<byte[]> transform) {
        // 读旧值仅用于索引同步（newValue==null 时移除旧向量）；
        // 原子 update 本身由底层段锁保证，索引同步为尽力而为。
        byte[] old = delegate.get(key);
        byte[] updated = atomic().update(key, transform);
        syncValueChange(key, old, updated);
        return updated;
    }

    private void indexIfVector(byte[] key, byte[] value) {
        if (isVector(value)) {
            vectorStore.put(new Embedding(
                    new String(key, StandardCharsets.UTF_8),
                    MultiModelCodec.decodeVector(value)));
            if (metrics != null) {
                metrics.recordVectorWrite();
            }
        }
    }

    /** 值变更后的索引同步：旧向量移除、新向量写入。 */
    private void syncValueChange(byte[] key, byte[] oldValue,
                                 byte[] newValue) {
        if (isVector(oldValue)) {
            vectorStore.delete(new String(key, StandardCharsets.UTF_8));
            if (metrics != null) {
                metrics.recordVectorDelete();
            }
        }
        if (isVector(newValue)) {
            indexIfVector(key, newValue);
        }
    }

    private static boolean isVector(byte[] value) {
        return value != null
                && TypedValueCodec.typeOf(value) == ValueType.VECTOR;
    }
}
