package io.tieringkv.storage.wal;

import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.WriteOp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * WAL 写路径装饰器（ADR-0016 + v6.5.0 Sharded WAL 零分配直写与同分片批量路径）：
 * 先 append WAL，后应用 MemTable；命令层无感知。TTL 过期不落盘（可由 PUT 记录推导）。
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
        int shardId = wal.routeShard(key);
        wal.withKeyLock(key, () -> {
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, value, ttlMillis);
            try {
                delegate.put(key, value, ttlMillis);
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public byte[] get(byte[] key) {
        return delegate.get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.DELETE, System.currentTimeMillis(), key, null, NO_TTL);
            try {
                return delegate.delete(key);
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    /**
     * 同分片零拷贝批量写（v6.5.0 Phase 2）：
     * 1) 升序批量锁住涉及的 keyLock 条带（最多 16 个且与其他分片严格互斥）；
     * 2) 整批一次性编码直写 WAL 分片（1 次 write + 至多 1 次 fdatasync）；
     * 3) WAL 成功后批量应用 MemTable 并释放低水位令牌；WAL 失败则零条进入 MemTable。
     */
    @Override
    public long applyWriteBatch(int shardHint, List<WriteOp> ops) {
        int count = ops.size();
        if (count == 0) {
            return 0L;
        }
        if (count == 1) {
            WriteOp op = ops.get(0);
            if (op.delete()) {
                return delete(op.key()) ? 1L : 0L;
            }
            put(op.key(), op.value(), op.ttlMillis());
            return 0L;
        }
        int targetShard = wal.routeShard(ops.get(0).key());
        boolean singleShard = true;
        if (wal.shardCount() > 1) {
            for (int i = 1; i < count; i++) {
                if (wal.routeShard(ops.get(i).key()) != targetShard) {
                    singleShard = false;
                    break;
                }
            }
        }
        if (!singleShard) {
            long mask = 0L;
            for (int i = 0; i < count; i++) {
                WriteOp op = ops.get(i);
                if (op.delete()) {
                    if (delete(op.key()) && i < 64) {
                        mask |= (1L << i);
                    }
                } else {
                    put(op.key(), op.value(), op.ttlMillis());
                }
            }
            return mask;
        }

        final int shardId = targetShard;
        long now = System.currentTimeMillis();
        return wal.withBatchKeyLocks(ops, () -> {
            long token = wal.appendBatchAndTrack(shardId, ops, now);
            try {
                long deletedMask = 0L;
                for (int i = 0; i < count; i++) {
                    WriteOp op = ops.get(i);
                    if (op.delete()) {
                        if (delegate.delete(op.key()) && i < 64) {
                            deletedMask |= (1L << i);
                        }
                    } else {
                        delegate.put(op.key(), op.value(), op.ttlMillis());
                    }
                }
                return deletedMask;
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public boolean hasTombstone(byte[] key) {
        return delegate.hasTombstone(key);
    }

    @Override
    public void clear() {
        java.util.List<byte[]> keys = new java.util.ArrayList<>();
        try (StorageIterator iterator = delegate.iterator()) {
            while (iterator.hasNext()) {
                keys.add(iterator.next().key());
            }
        }
        long now = System.currentTimeMillis();
        for (byte[] key : keys) {
            int shardId = wal.routeShard(key);
            wal.withKeyLock(key, () -> {
                long token = wal.appendSingleAndTrack(
                        shardId, WALEntry.Operation.DELETE, now, key, null, NO_TTL);
                try {
                    delegate.removePhysical(key);
                } finally {
                    wal.completeApply(shardId, token);
                }
            });
        }
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

    // ---------- 原子字符串操作（WAL-first 委托，ADR-0269；按 key 分片互斥） ----------

    @Override
    public long increment(byte[] key, long delta) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] current = delegate.get(key);
            long base = current == null ? 0
                    : Long.parseLong(new String(current,
                    StandardCharsets.UTF_8));
            long next = Math.addExact(base, delta);
            byte[] nextBytes = Long.toString(next)
                    .getBytes(StandardCharsets.UTF_8);
            long ttl = remainingTtlForWal(key);
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, nextBytes, ttl);
            try {
                applyPreservingTtl(key, nextBytes);
            } finally {
                wal.completeApply(shardId, token);
            }
            return next;
        });
    }

    @Override
    public int append(byte[] key, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("null value");
        }
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] current = delegate.get(key);
            byte[] base = current == null ? new byte[0] : current;
            byte[] merged = Arrays.copyOf(base,
                    base.length + value.length);
            System.arraycopy(value, 0, merged, base.length,
                    value.length);
            long ttl = remainingTtlForWal(key);
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, merged, ttl);
            try {
                applyPreservingTtl(key, merged);
            } finally {
                wal.completeApply(shardId, token);
            }
            return merged.length;
        });
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] old = delegate.get(key);
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, value, NO_TTL);
            try {
                if (delegate instanceof AtomicStringOps atomic) {
                    return atomic.getSet(key, value);
                }
                delegate.put(key, value);
                return old;
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public byte[] getAndSetPreservingTtl(
            byte[] key, byte[] value) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] old = delegate.get(key);
            long ttl = remainingTtlForWal(key);
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, value, ttl);
            try {
                applyPreservingTtl(key, value);
            } finally {
                wal.completeApply(shardId, token);
            }
            return old;
        });
    }

    @Override
    public byte[] getDelete(byte[] key) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] old = delegate.get(key);
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.DELETE, System.currentTimeMillis(), key, null, NO_TTL);
            try {
                if (delegate instanceof AtomicStringOps atomic) {
                    return atomic.getDelete(key);
                }
                delegate.delete(key);
                return old;
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public boolean putIfAbsent(byte[] key,
                               byte[] value) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            if (delegate.exists(key)) {
                return false;
            }
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, value, NO_TTL);
            try {
                if (delegate instanceof AtomicStringOps atomic) {
                    return atomic.putIfAbsent(key, value);
                }
                delegate.put(key, value);
                return true;
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public long ttlMillis(byte[] key) {
        return delegate instanceof AtomicStringOps atomic
                ? atomic.ttlMillis(key) : -1;
    }

    @Override
    public boolean persist(byte[] key) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            long ttl = remainingTtlForWal(key);
            if (ttl == NO_TTL) {
                return false;
            }
            byte[] value = delegate.get(key);
            if (value == null) {
                return false;
            }
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, value, NO_TTL);
            try {
                if (delegate instanceof AtomicStringOps atomic) {
                    return atomic.persist(key);
                }
                delegate.put(key, value);
                return true;
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public boolean expireAt(byte[] key,
                            long expireAtMillis) {
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] value = delegate.get(key);
            if (value == null) {
                return false;
            }
            long ttl = Math.max(0,
                    expireAtMillis - System.currentTimeMillis());
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, value, ttl);
            try {
                if (delegate instanceof AtomicStringOps atomic) {
                    return atomic.expireAt(key, expireAtMillis);
                }
                delegate.put(key, value, ttl);
                return true;
            } finally {
                wal.completeApply(shardId, token);
            }
        });
    }

    @Override
    public byte[] update(
            byte[] key, UnaryOperator<byte[]> transform) {
        if (transform == null) {
            throw new IllegalArgumentException(
                    "transform required");
        }
        int shardId = wal.routeShard(key);
        return wal.withKeyLock(key, () -> {
            byte[] current = delegate.get(key);
            byte[] next = transform.apply(current);
            if (next == null) {
                long token = wal.appendSingleAndTrack(
                        shardId, WALEntry.Operation.DELETE, System.currentTimeMillis(), key, null, NO_TTL);
                try {
                    delegate.delete(key);
                } finally {
                    wal.completeApply(shardId, token);
                }
                return null;
            }
            long ttl = remainingTtlForWal(key);
            long token = wal.appendSingleAndTrack(
                    shardId, WALEntry.Operation.PUT, System.currentTimeMillis(), key, next, ttl);
            try {
                applyPreservingTtl(key, next);
            } finally {
                wal.completeApply(shardId, token);
            }
            return next;
        });
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
