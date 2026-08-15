package io.tieringkv.storage.memory;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.FlushManager;
import io.tieringkv.storage.types.ByteArrayKey;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Active / Immutable MemTable 管理器（ADR-0324，TD-013）：写入始终命中
 * active，水位触发 rotate（active → immutable + WAL 段轮转），后台 flush
 * immutable → SSTable，读路径按"新表优先"合并。
 *
 * <p>崩溃恢复：immutable 为内存态，全部写操作已入 WAL，恢复时
 * WAL 重放进新 active 即可重建（FlushManager 已按表做 WAL checkpoint）。
 */
public final class MemTableManager
        implements StorageEngine, AutoCloseable {

    private final MemoryManager memoryManager;
    private final WALManager wal;
    private final List<MemTable> immutable =
            new CopyOnWriteArrayList<>();
    private volatile MemTable active;

    public MemTableManager(MemoryManager memoryManager,
                           WALManager wal) {
        if (memoryManager == null) {
            throw new IllegalArgumentException(
                    "memoryManager required");
        }
        this.memoryManager = memoryManager;
        this.wal = wal;
        this.active = MemTable.create(memoryManager);
    }

    public MemTable active() {
        return active;
    }

    public MemoryManager memoryManager() {
        return memoryManager;
    }

    public List<MemTable> immutableTables() {
        return List.copyOf(immutable);
    }

    public int immutableCount() {
        return immutable.size();
    }

    /** 轮转：active → immutable，新建 active，WAL 段轮转。 */
    public MemTable rotate() {
        MemTable old = active;
        immutable.add(old);
        active = MemTable.create(memoryManager);
        if (wal != null) {
            wal.rotate();
        }
        return old;
    }

    /** 后台 flush 最老 immutable → cold；无 immutable 返回 empty。 */
    public Optional<MemTable> flushOldest(ColdStorageEngine cold)
            throws IOException {
        if (immutable.isEmpty()) {
            return Optional.empty();
        }
        MemTable oldest = immutable.get(0);
        FlushManager.flush(oldest, wal, cold);
        immutable.remove(0);
        return Optional.of(oldest);
    }

    // ---------- StorageEngine：写路径 active ----------

    @Override
    public void put(byte[] key, byte[] value) {
        active.put(key, value);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        active.put(key, value, ttlMillis);
    }

    @Override
    public boolean delete(byte[] key) {
        // 跨表删除：对全部表执行 delete——有活值的表写 tombstone，
        // 读合并按新表优先自然被 tombstone 覆盖（不再回退旧表）。
        boolean removed = active.delete(key);
        for (int i = immutable.size() - 1; i >= 0; i--) {
            removed |= immutable.get(i).delete(key);
        }
        return removed;
    }

    @Override
    public boolean removePhysical(byte[] key) {
        boolean removed = false;
        for (MemTable table : immutable) {
            removed |= table.removePhysical(key);
        }
        removed |= active.removePhysical(key);
        return removed;
    }

    @Override
    public boolean exists(byte[] key) {
        return get(key) != null;
    }

    // ---------- StorageEngine：读路径新表优先合并 ----------

    @Override
    public byte[] get(byte[] key) {
        KeyValueEntry entry = getEntry(key);
        return entry == null ? null : entry.value();
    }

    public KeyValueEntry getEntry(byte[] key) {
        // 新表优先：先 active，随后 immutable 新→旧
        KeyValueEntry entry = active.getEntry(key);
        if (entry != null) {
            return entry;
        }
        for (int i = immutable.size() - 1; i >= 0; i--) {
            entry = immutable.get(i).getEntry(key);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    /** 跨表版本守卫物理删除（迁移/GC 用，ADR-0324）。 */
    public boolean removePhysicalIfVersion(byte[] key,
                                           long expectedVersion) {
        boolean removed = false;
        for (int i = immutable.size() - 1; i >= 0; i--) {
            removed |= immutable.get(i).removePhysicalIfVersion(
                    key, expectedVersion);
        }
        removed |= active.removePhysicalIfVersion(
                key, expectedVersion);
        return removed;
    }

    @Override
    public long size() {
        long total = active.size();
        for (MemTable table : immutable) {
            total += table.size();
        }
        return total;
    }

    /** 合并快照迭代器：全部表按 key 有序，新表覆盖旧表。 */
    @Override
    public StorageIterator iterator() {
        TreeMap<ByteArrayKey, KeyValueEntry> merged = new TreeMap<>(
                (a, b) -> Arrays.compare(a.data(), b.data()));
        for (MemTable table : immutable) {
            collect(table, merged);
        }
        collect(active, merged);
        List<KeyValueEntry> values = new ArrayList<>(merged.values());
        return new SnapshotIterator(values);
    }

    private static void collect(
            MemTable table,
            TreeMap<ByteArrayKey, KeyValueEntry> merged) {
        try (StorageIterator iterator = table.iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                merged.put(new ByteArrayKey(entry.key()), entry);
            }
        }
    }

    @Override
    public void clear() {
        for (MemTable table : immutable) {
            table.clear();
        }
        active.clear();
    }

    @Override
    public void close() {
        for (MemTable table : immutable) {
            table.close();
        }
        active.close();
    }

    /** 内存快照迭代器。 */
    private static final class SnapshotIterator
            implements StorageIterator {
        private final List<KeyValueEntry> entries;
        private int index;

        private SnapshotIterator(List<KeyValueEntry> entries) {
            this.entries = entries;
        }

        @Override
        public boolean hasNext() {
            return index < entries.size();
        }

        @Override
        public KeyValueEntry next() {
            return entries.get(index++);
        }

        @Override
        public void close() {
            // 内存快照无需释放
        }
    }
}
