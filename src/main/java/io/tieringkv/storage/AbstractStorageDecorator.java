package io.tieringkv.storage;

import java.util.function.UnaryOperator;

/**
 * StorageEngine / AtomicStringOps 装饰器基类（ADR-0351）。
 *
 * <p>背景：命令层通过 {@code storage instanceof AtomicStringOps} 决定
 * TTL/EXPIRE/PERSIST/INCR/APPEND 等命令是否走原子路径。此前生产链上的
 * 四个装饰器只实现 {@link StorageEngine}，导致最外层实例不满足 instanceof，
 * 命令层静默回退到 get+put 非原子实现，并造成 TTL 查询恒为 -1
 * （实践运行 SETEX→TTL 缺陷）。
 *
 * <p>本基类统一提供两类委托：核心 StorageEngine 方法与全部
 * AtomicStringOps 方法。子类按需覆写 put/get/delete 及原子写操作，
 * 以注入缓存失效、背压、热度统计、向量索引同步等横切逻辑；
 * 未覆写的方法保持透传语义。
 *
 * <p>约定：底层 delegate 必须实现 {@link AtomicStringOps}，否则
 * 原子操作显式失败（UnsupportedOperationException），不再静默回退。
 */
public abstract class AbstractStorageDecorator
        implements StorageEngine, AtomicStringOps {

    protected final StorageEngine delegate;

    protected AbstractStorageDecorator(StorageEngine delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate required");
        }
        this.delegate = delegate;
    }

    // ---------- StorageEngine 委托 ----------

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        delegate.put(key, value, ttlMillis);
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        return delegate.delete(key);
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

    /** 版本桥接（两接口均有默认实现）：透传底层版本供热缓存新鲜度校验。 */
    @Override
    public long versionOf(byte[] key) {
        return delegate.versionOf(key);
    }

    // ---------- AtomicStringOps 委托（ADR-0351） ----------

    @Override
    public long increment(byte[] key, long delta) {
        return atomic().increment(key, delta);
    }

    @Override
    public int append(byte[] key, byte[] value) {
        return atomic().append(key, value);
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        return atomic().getSet(key, value);
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        return atomic().getAndSetPreservingTtl(key, value);
    }

    @Override
    public byte[] getDelete(byte[] key) {
        return atomic().getDelete(key);
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        return atomic().putIfAbsent(key, value);
    }

    @Override
    public long ttlMillis(byte[] key) {
        return atomic().ttlMillis(key);
    }

    @Override
    public boolean persist(byte[] key) {
        return atomic().persist(key);
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        return atomic().expireAt(key, expireAtMillis);
    }

    @Override
    public byte[] update(byte[] key, UnaryOperator<byte[]> transform) {
        return atomic().update(key, transform);
    }

    /** 返回底层原子能力；delegate 不支持时显式失败而非静默回退。 */
    protected final AtomicStringOps atomic() {
        if (delegate instanceof AtomicStringOps atomic) {
            return atomic;
        }
        throw new UnsupportedOperationException(
                delegate.getClass().getName()
                        + " does not implement AtomicStringOps");
    }
}
