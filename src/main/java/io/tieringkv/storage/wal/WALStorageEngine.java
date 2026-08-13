package io.tieringkv.storage.wal;

import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.UnaryOperator;

/**
 * WAL 写路径装饰器（ADR-0016）：先 append WAL，后应用 MemTable；
 * 命令层无感知。TTL 过期不落盘（可由 PUT 记录推导）。
 */
public final class WALStorageEngine
        implements StorageEngine, AtomicStringOps {

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

    // ---------- 原子字符串操作（WAL-first 委托，ADR-0269） ----------

    @Override
    public synchronized long increment(byte[] key, long delta) {
        byte[] current = delegate.get(key);
        long base = current == null ? 0
                : Long.parseLong(new String(current,
                StandardCharsets.UTF_8));
        long next = Math.addExact(base, delta);
        byte[] nextBytes = Long.toString(next)
                .getBytes(StandardCharsets.UTF_8);
        long ttl = remainingTtlForWal(key);
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                nextBytes, ttl, 0));
        applyPreservingTtl(key, nextBytes);
        return next;
    }

    @Override
    public synchronized int append(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("null value");
        }
        byte[] current = delegate.get(key);
        byte[] base = current == null ? new byte[0] : current;
        byte[] merged = Arrays.copyOf(base,
                base.length + value.length);
        System.arraycopy(value, 0, merged, base.length,
                value.length);
        long ttl = remainingTtlForWal(key);
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                merged, ttl, 0));
        applyPreservingTtl(key, merged);
        return merged.length;
    }

    @Override
    public synchronized byte[] getSet(byte[] key, byte[] value) {
        byte[] old = delegate.get(key);
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                value, NO_TTL, 0));
        if (delegate instanceof AtomicStringOps atomic) {
            return atomic.getSet(key, value);
        }
        delegate.put(key, value);
        return old;
    }

    @Override
    public synchronized byte[] getAndSetPreservingTtl(
            byte[] key, byte[] value) {
        byte[] old = delegate.get(key);
        long ttl = remainingTtlForWal(key);
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                value, ttl, 0));
        applyPreservingTtl(key, value);
        return old;
    }

    @Override
    public synchronized byte[] getDelete(byte[] key) {
        byte[] old = delegate.get(key);
        wal.append(WALEntry.delete(System.currentTimeMillis(),
                key, 0));
        if (delegate instanceof AtomicStringOps atomic) {
            return atomic.getDelete(key);
        }
        delegate.delete(key);
        return old;
    }

    @Override
    public synchronized boolean putIfAbsent(byte[] key,
                                            byte[] value) {
        if (delegate.exists(key)) {
            return false;
        }
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                value, NO_TTL, 0));
        if (delegate instanceof AtomicStringOps atomic) {
            return atomic.putIfAbsent(key, value);
        }
        delegate.put(key, value);
        return true;
    }

    @Override
    public long ttlMillis(byte[] key) {
        return delegate instanceof AtomicStringOps atomic
                ? atomic.ttlMillis(key) : -1;
    }

    @Override
    public synchronized boolean persist(byte[] key) {
        long ttl = remainingTtlForWal(key);
        if (ttl == NO_TTL) {
            return false;
        }
        byte[] value = delegate.get(key);
        if (value == null) {
            return false;
        }
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                value, NO_TTL, 0));
        if (delegate instanceof AtomicStringOps atomic) {
            return atomic.persist(key);
        }
        delegate.put(key, value);
        return true;
    }

    @Override
    public synchronized boolean expireAt(byte[] key,
                                         long expireAtMillis) {
        byte[] value = delegate.get(key);
        if (value == null) {
            return false;
        }
        long ttl = Math.max(0,
                expireAtMillis - System.currentTimeMillis());
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                value, ttl, 0));
        if (delegate instanceof AtomicStringOps atomic) {
            return atomic.expireAt(key, expireAtMillis);
        }
        delegate.put(key, value, ttl);
        return true;
    }

    @Override
    public synchronized byte[] update(
            byte[] key, UnaryOperator<byte[]> transform) {
        if (transform == null) {
            throw new IllegalArgumentException(
                    "transform required");
        }
        byte[] current = delegate.get(key);
        byte[] next = transform.apply(current);
        if (next == null) {
            wal.append(WALEntry.delete(System.currentTimeMillis(),
                    key, 0));
            delegate.delete(key);
            return null;
        }
        long ttl = remainingTtlForWal(key);
        wal.append(WALEntry.put(System.currentTimeMillis(), key,
                next, ttl, 0));
        applyPreservingTtl(key, next);
        return next;
    }

    @Override
    public long versionOf(byte[] key) {
        return delegate instanceof AtomicStringOps atomic
                ? atomic.versionOf(key) : 0;
    }

    private void applyPreservingTtl(byte[] key, byte[] value) {
        if (delegate instanceof AtomicStringOps atomic) {
            atomic.getAndSetPreservingTtl(key, value);
        } else {
            delegate.put(key, value,
                    remainingTtlForWal(key));
        }
    }

    private long remainingTtlForWal(byte[] key) {
        if (delegate instanceof AtomicStringOps atomic) {
            long ttl = atomic.ttlMillis(key);
            return ttl == -2 ? NO_TTL : ttl;
        }
        return NO_TTL;
    }
}
