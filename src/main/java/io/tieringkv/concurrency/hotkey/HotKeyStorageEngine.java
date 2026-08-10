package io.tieringkv.concurrency.hotkey;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

/** 热点读缓存装饰器（ADR-0025）：读走缓存，写前/写后失效。 */
public final class HotKeyStorageEngine implements StorageEngine {

    private final StorageEngine delegate;
    private final HotKeyReadCache cache;

    public HotKeyStorageEngine(StorageEngine delegate, HotKeyReadCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
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
}
