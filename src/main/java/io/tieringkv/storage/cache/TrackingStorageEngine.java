package io.tieringkv.storage.cache;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.TimeSource;

/**
 * StorageEngine 装饰器（ADR-0010）：在命令路径上产生 AccessEvent，
 * 并在 PUT 后触发 EvictionManager 的容量检查。MemTable 核心不变。
 */
public final class TrackingStorageEngine implements StorageEngine {

    private final StorageEngine delegate;
    private final EvictionManager evictionManager;
    private final TimeSource timeSource;

    public TrackingStorageEngine(StorageEngine delegate, EvictionManager evictionManager) {
        this(delegate, evictionManager, System::currentTimeMillis);
    }

    public TrackingStorageEngine(
            StorageEngine delegate,
            EvictionManager evictionManager,
            TimeSource timeSource) {
        this.delegate = delegate;
        this.evictionManager = evictionManager;
        this.timeSource = timeSource;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        delegate.put(key, value, ttlMillis);
        record(AccessEvent.AccessOperation.PUT, key, KeyValueEntry.sizeOf(key, value));
        evictionManager.maybeEvict();
    }

    @Override
    public byte[] get(byte[] key) {
        byte[] value = delegate.get(key);
        if (value != null) {
            record(AccessEvent.AccessOperation.GET, key, 0);
        }
        return value;
    }

    @Override
    public boolean delete(byte[] key) {
        boolean removed = delegate.delete(key);
        if (removed) {
            record(AccessEvent.AccessOperation.DELETE, key, 0);
        }
        return removed;
    }

    @Override
    public boolean exists(byte[] key) {
        boolean found = delegate.exists(key);
        if (found) {
            record(AccessEvent.AccessOperation.GET, key, 0);
        }
        return found;
    }

    @Override
    public StorageIterator iterator() {
        return delegate.iterator();
    }

    @Override
    public long size() {
        return delegate.size();
    }

    private void record(AccessEvent.AccessOperation operation, byte[] key, int sizeBytes) {
        evictionManager.onAccess(new AccessEvent(key, operation, timeSource.nowMillis(), sizeBytes));
    }
}
