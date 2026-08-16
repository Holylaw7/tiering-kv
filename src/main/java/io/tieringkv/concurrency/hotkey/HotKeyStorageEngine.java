package io.tieringkv.concurrency.hotkey;

import io.tieringkv.storage.AbstractStorageDecorator;
import io.tieringkv.storage.StorageEngine;

/**
 * 热点读缓存装饰器（ADR-0025）：读走缓存，写前/写后失效。
 * 原子写（INCR/APPEND/EXPIRE 等）同样失效缓存（ADR-0351），
 * 避免热点读返回旧值。
 */
public final class HotKeyStorageEngine extends AbstractStorageDecorator {

    private final HotKeyReadCache cache;

    public HotKeyStorageEngine(StorageEngine delegate, HotKeyReadCache cache) {
        super(delegate);
        this.cache = cache;
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        cache.invalidate(key);
        delegate.put(key, value, ttlMillis);
        cache.invalidate(key);
    }

    @Override
    public byte[] get(byte[] key) {
        return cache.get(key, System.currentTimeMillis());
    }

    @Override
    public boolean delete(byte[] key) {
        cache.invalidate(key);
        boolean removed = delegate.delete(key);
        cache.invalidate(key);
        return removed;
    }

    @Override
    public long increment(byte[] key, long delta) {
        cache.invalidate(key);
        long result = atomic().increment(key, delta);
        cache.invalidate(key);
        return result;
    }

    @Override
    public int append(byte[] key, byte[] value) {
        cache.invalidate(key);
        int result = atomic().append(key, value);
        cache.invalidate(key);
        return result;
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        cache.invalidate(key);
        byte[] old = atomic().getSet(key, value);
        cache.invalidate(key);
        return old;
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        cache.invalidate(key);
        byte[] old = atomic().getAndSetPreservingTtl(key, value);
        cache.invalidate(key);
        return old;
    }

    @Override
    public byte[] getDelete(byte[] key) {
        cache.invalidate(key);
        byte[] old = atomic().getDelete(key);
        cache.invalidate(key);
        return old;
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        cache.invalidate(key);
        boolean wrote = atomic().putIfAbsent(key, value);
        cache.invalidate(key);
        return wrote;
    }

    @Override
    public boolean persist(byte[] key) {
        cache.invalidate(key);
        boolean ok = atomic().persist(key);
        cache.invalidate(key);
        return ok;
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        cache.invalidate(key);
        boolean ok = atomic().expireAt(key, expireAtMillis);
        cache.invalidate(key);
        return ok;
    }

    @Override
    public byte[] update(byte[] key, java.util.function.UnaryOperator<byte[]> transform) {
        cache.invalidate(key);
        byte[] updated = atomic().update(key, transform);
        cache.invalidate(key);
        return updated;
    }
}
