package io.tieringkv.storage.wal;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

/**
 * WAL 写路径装饰器（ADR-0016）：先 append WAL，后应用 MemTable；
 * 命令层无感知。TTL 过期不落盘（可由 PUT 记录推导）。
 */
public final class WALStorageEngine implements StorageEngine {

    private final WALManager wal;
    private final StorageEngine delegate;

    public WALStorageEngine(WALManager wal, StorageEngine delegate) {
        this.wal = wal;
        this.delegate = delegate;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        wal.append(WALEntry.put(System.currentTimeMillis(), key, value, ttlMillis, 0));
        delegate.put(key, value, ttlMillis);
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        wal.append(WALEntry.delete(System.currentTimeMillis(), key, 0));
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
}
