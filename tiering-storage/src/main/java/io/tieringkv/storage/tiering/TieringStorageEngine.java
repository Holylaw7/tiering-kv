package io.tieringkv.storage.tiering;

import io.tieringkv.storage.AbstractStorageDecorator;
import io.tieringkv.storage.StorageEngine;

/**
 * 分层写路径装饰器（ADR-0020）：写前背压检查，写后水位检查（触发异步 Flush）。
 * 命令层无感知。原子写（INCR/APPEND/EXPIRE 等）同样走背压（ADR-0351）。
 */
public final class TieringStorageEngine extends AbstractStorageDecorator {

    private final TieringController controller;

    public TieringStorageEngine(StorageEngine delegate, TieringController controller) {
        super(delegate);
        this.controller = controller;
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
    public boolean delete(byte[] key) {
        boolean removed = delegate.delete(key);
        controller.onWriteCompleted();
        return removed;
    }

    @Override
    public long increment(byte[] key, long delta) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        long result = atomic().increment(key, delta);
        controller.onWriteCompleted();
        return result;
    }

    @Override
    public int append(byte[] key, byte[] value) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        int result = atomic().append(key, value);
        controller.onWriteCompleted();
        return result;
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        byte[] old = atomic().getSet(key, value);
        controller.onWriteCompleted();
        return old;
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        byte[] old = atomic().getAndSetPreservingTtl(key, value);
        controller.onWriteCompleted();
        return old;
    }

    @Override
    public byte[] getDelete(byte[] key) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        byte[] old = atomic().getDelete(key);
        controller.onWriteCompleted();
        return old;
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        boolean wrote = atomic().putIfAbsent(key, value);
        controller.onWriteCompleted();
        return wrote;
    }

    @Override
    public boolean persist(byte[] key) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        boolean ok = atomic().persist(key);
        controller.onWriteCompleted();
        return ok;
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        boolean ok = atomic().expireAt(key, expireAtMillis);
        controller.onWriteCompleted();
        return ok;
    }

    @Override
    public byte[] update(byte[] key, java.util.function.UnaryOperator<byte[]> transform) {
        if (!controller.awaitWritable()) {
            throw new BackpressureException("memory critical: writes limited");
        }
        byte[] updated = atomic().update(key, transform);
        controller.onWriteCompleted();
        return updated;
    }
}
