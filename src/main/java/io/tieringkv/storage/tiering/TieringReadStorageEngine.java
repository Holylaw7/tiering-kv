package io.tieringkv.storage.tiering;

import io.tieringkv.storage.AbstractStorageDecorator;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;

/**
 * 分层读路径装饰器（TD-086）：补齐"迁移后可读"。
 *
 * <p>背景：迁移只把数据写进冷层（`cold.put`），主读链从不查询冷层，
 * 导致超水位后 `GET` 返回 nil、`DBSIZE` 只统计热层——表现为"超配即丢数据"。
 *
 * <p>本装饰器置于读链最外层，提供：
 * <ul>
 *   <li>{@code get/exists}：热层未命中时回查冷层；</li>
 *   <li>{@code delete}：热层删除的同时给冷层写墓碑，避免旧值复活；</li>
 *   <li>{@code size}：热层存活数 + 冷层存活数（DBSIZE 语义修正）；</li>
 *   <li>{@code iterator}：两层归并去重，热层优先（SCAN / FLUSHDB 语义修正）。</li>
 * </ul>
 *
 * <p>写路径与原子写仍由下层装饰器负责；冷层回填（copy-up）见
 * {@code TieringAtomicCopyUp}（TD-086 S3）。
 */
public final class TieringReadStorageEngine extends AbstractStorageDecorator {

    private final ColdStorageEngine cold;

    public TieringReadStorageEngine(StorageEngine delegate, ColdStorageEngine cold) {
        super(delegate);
        if (cold == null) {
            throw new IllegalArgumentException("cold storage required");
        }
        this.cold = cold;
    }

    @Override
    public byte[] get(byte[] key) {
        byte[] value = delegate.get(key);
        return value != null ? value : cold.get(key);
    }

    @Override
    public boolean exists(byte[] key) {
        return delegate.exists(key) || cold.entry(key) != null;
    }

    @Override
    public boolean delete(byte[] key) {
        boolean removedHot = delegate.delete(key);
        boolean removedCold = false;
        KeyValueEntry entry = cold.entry(key);
        if (entry != null) {
            cold.delete(key);
            removedCold = true;
        }
        return removedHot || removedCold;
    }

    @Override
    public long size() {
        return delegate.size() + cold.liveCount();
    }

    @Override
    public StorageIterator iterator() {
        return new UnionStorageIterator(delegate.iterator(), cold.iterator());
    }

    // ---------- 原子写：冷层键先 copy-up 再执行（TD-086 S3） ----------

    /** 保留冷层原 TTL 的哨兵值。 */
    private static final long KEEP_TTL = Long.MIN_VALUE;

    /**
     * 把冷层条目的当前状态搬回热层，然后清掉冷层副本。
     *
     * <p>顺序很关键：先写热层（可能触发背压异常），成功后再删冷层，
     * 避免中途失败导致数据丢失。
     *
     * @return 是否发生了回填
     */
    private boolean copyUp(byte[] key, long ttlOverride) {
        if (delegate.get(key) != null) {
            return false;
        }
        KeyValueEntry entry = cold.entry(key);
        if (entry == null) {
            return false;
        }
        long ttl = ttlOverride == KEEP_TTL ? remainingTtl(entry) : ttlOverride;
        delegate.put(key, entry.value(), ttl);
        cold.delete(key);
        return true;
    }

    private static long remainingTtl(KeyValueEntry entry) {
        if (entry.expireTimestamp() < 0) {
            return NO_TTL;
        }
        long remaining = entry.expireTimestamp() - System.currentTimeMillis();
        return remaining > 0 ? remaining : 1;
    }

    @Override
    public long increment(byte[] key, long delta) {
        copyUp(key, KEEP_TTL);
        return atomic().increment(key, delta);
    }

    @Override
    public int append(byte[] key, byte[] value) {
        copyUp(key, KEEP_TTL);
        return atomic().append(key, value);
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        byte[] old = cold.get(key);
        copyUp(key, KEEP_TTL);
        byte[] hotOld = atomic().getSet(key, value);
        return hotOld != null ? hotOld : old;
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        byte[] old = cold.get(key);
        copyUp(key, KEEP_TTL);
        byte[] hotOld = atomic().getAndSetPreservingTtl(key, value);
        return hotOld != null ? hotOld : old;
    }

    @Override
    public byte[] getDelete(byte[] key) {
        byte[] old = cold.get(key);
        boolean copied = copyUp(key, KEEP_TTL);
        byte[] hotOld = atomic().getDelete(key);
        return hotOld != null ? hotOld : (copied ? null : old);
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        // SETNX 语义：冷层已存在即视为存在，不得覆盖
        if (cold.entry(key) != null) {
            return false;
        }
        return atomic().putIfAbsent(key, value);
    }

    @Override
    public byte[] update(byte[] key, java.util.function.UnaryOperator<byte[]> transform) {
        copyUp(key, KEEP_TTL);
        return atomic().update(key, transform);
    }

    @Override
    public long ttlMillis(byte[] key) {
        long ttl = atomic().ttlMillis(key);
        if (ttl != -2) {
            return ttl;
        }
        KeyValueEntry entry = cold.entry(key);
        if (entry == null) {
            return -2;
        }
        return entry.expireTimestamp() < 0 ? -1 : remainingTtl(entry);
    }

    @Override
    public boolean persist(byte[] key) {
        if (delegate.get(key) == null) {
            KeyValueEntry entry = cold.entry(key);
            if (entry == null) {
                return false;
            }
            if (entry.expireTimestamp() < 0) {
                return false;
            }
            copyUp(key, NO_TTL);
            return true;
        }
        return atomic().persist(key);
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        if (delegate.get(key) == null) {
            KeyValueEntry entry = cold.entry(key);
            if (entry == null) {
                return false;
            }
            long ttl = expireAtMillis - System.currentTimeMillis();
            copyUp(key, ttl > 0 ? ttl : 1);
            return true;
        }
        return atomic().expireAt(key, expireAtMillis);
    }
}
