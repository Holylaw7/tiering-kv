package io.tieringkv.storage.cache;

import io.tieringkv.storage.AbstractStorageDecorator;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.TimeSource;

/**
 * StorageEngine 装饰器（ADR-0010）：在命令路径上产生 AccessEvent，
 * 并在 PUT 后触发 EvictionManager 的容量检查。原子写/读操作同样
 * 产生事件（ADR-0351），保证 INCR/APPEND/EXPIRE/TTL 参与热度统计。
 */
public final class TrackingStorageEngine extends AbstractStorageDecorator {

    private final EvictionManager evictionManager;
    private final TimeSource timeSource;

    public TrackingStorageEngine(StorageEngine delegate, EvictionManager evictionManager) {
        this(delegate, evictionManager, System::currentTimeMillis);
    }

    public TrackingStorageEngine(
            StorageEngine delegate,
            EvictionManager evictionManager,
            TimeSource timeSource) {
        super(delegate);
        this.evictionManager = evictionManager;
        this.timeSource = timeSource;
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
    public long increment(byte[] key, long delta) {
        long result = atomic().increment(key, delta);
        record(AccessEvent.AccessOperation.PUT, key, 0);
        return result;
    }

    @Override
    public int append(byte[] key, byte[] value) {
        int result = atomic().append(key, value);
        record(AccessEvent.AccessOperation.PUT, key, 0);
        return result;
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        byte[] old = atomic().getSet(key, value);
        record(AccessEvent.AccessOperation.PUT, key, 0);
        return old;
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        byte[] old = atomic().getAndSetPreservingTtl(key, value);
        record(AccessEvent.AccessOperation.PUT, key, 0);
        return old;
    }

    @Override
    public byte[] getDelete(byte[] key) {
        byte[] old = atomic().getDelete(key);
        record(AccessEvent.AccessOperation.DELETE, key, 0);
        return old;
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        boolean wrote = atomic().putIfAbsent(key, value);
        if (wrote) {
            record(AccessEvent.AccessOperation.PUT, key, 0);
        }
        return wrote;
    }

    @Override
    public long ttlMillis(byte[] key) {
        long millis = atomic().ttlMillis(key);
        record(AccessEvent.AccessOperation.GET, key, 0);
        return millis;
    }

    @Override
    public boolean persist(byte[] key) {
        boolean ok = atomic().persist(key);
        if (ok) {
            record(AccessEvent.AccessOperation.PUT, key, 0);
        }
        return ok;
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        boolean ok = atomic().expireAt(key, expireAtMillis);
        if (ok) {
            record(AccessEvent.AccessOperation.PUT, key, 0);
        }
        return ok;
    }

    @Override
    public byte[] update(byte[] key, java.util.function.UnaryOperator<byte[]> transform) {
        byte[] updated = atomic().update(key, transform);
        record(AccessEvent.AccessOperation.PUT, key, 0);
        return updated;
    }

    private void record(AccessEvent.AccessOperation operation, byte[] key, int sizeBytes) {
        evictionManager.onAccess(new AccessEvent(key, operation, timeSource.nowMillis(), sizeBytes));
    }
}
