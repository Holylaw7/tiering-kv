package io.tieringkv.storage.tiering;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

/**
 * 分层写路径装饰器（ADR-0020）：写前背压检查，写后水位检查（触发异步 Flush）。
 * 命令层无感知。
 */
public final class TieringStorageEngine implements StorageEngine {

    private final StorageEngine delegate;
    private final TieringController controller;

    public TieringStorageEngine(StorageEngine delegate, TieringController controller) {
        this.delegate = delegate;
        this.controller = controller;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        delegate.put(key, value, ttlMillis);
        controller.onWriteCompleted();
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        boolean removed = delegate.delete(key);
        controller.onWriteCompleted();
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
