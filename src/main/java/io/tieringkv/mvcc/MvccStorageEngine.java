package io.tieringkv.mvcc;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MVCC 存储适配器（ADR-0071）：底层 StorageEngine 保存
 * [userKey][commitTS] 版本；DELETE 以 tombstone 哨兵表示。
 */
public final class MvccStorageEngine {

    private final StorageEngine storage;
    // 内存版本索引：写入同步维护，读取 O(logN)，避免全表扫描 O(N²)
    private final Map<ByteKey, List<MvccEntry>> index =
            new ConcurrentHashMap<>();

    public MvccStorageEngine(StorageEngine storage) {
        this.storage = storage;
        rebuildIndex();
    }

    /** 启动/快照恢复：从底层存储重建版本索引（O(N) 一次）。 */
    private void rebuildIndex() {
        synchronized (index) {
            index.clear();
            try (StorageIterator iterator = storage.iterator()) {
                while (iterator.hasNext()) {
                    KeyValueEntry entry = iterator.next();
                    byte[] userKey = MvccKey.userKey(entry.key());
                    index.compute(new ByteKey(userKey), (key, list) -> {
                        List<MvccEntry> updated = list == null
                                ? new ArrayList<>() : new ArrayList<>(list);
                        updated.add(new MvccEntry(userKey, entry.value(),
                                MvccKey.startTS(entry.key()),
                                MvccKey.commitTS(entry.key()),
                                MvccKey.writeType(entry.key())));
                        updated.sort(Comparator.comparingLong(MvccEntry::commitTS));
                        return updated;
                    });
                }
            }
        }
    }

    public StorageEngine underlying() {
        return storage;
    }

    /** 写一个可见版本（PUT/DELETE）。 */
    public void putVersion(byte[] userKey, byte[] value, long startTS,
                           long commitTS, WriteType writeType) {
        storage.put(MvccKey.encode(userKey, startTS, commitTS, writeType),
                value == null ? new byte[0] : value);
        synchronized (index) {
            index.compute(new ByteKey(userKey), (key, list) -> {
                List<MvccEntry> updated = list == null
                        ? new ArrayList<>() : new ArrayList<>(list);
                updated.add(new MvccEntry(userKey, value, startTS,
                        commitTS, writeType));
                updated.sort(Comparator.comparingLong(MvccEntry::commitTS));
                return updated;
            });
        }
    }

    /** 删除指定版本（rollback/GC）。 */
    public void deleteVersion(byte[] userKey, long commitTS) {
        synchronized (index) {
            List<MvccEntry> list = index.get(new ByteKey(userKey));
            if (list == null) {
                return;
            }
            List<MvccEntry> updated = new ArrayList<>();
            for (MvccEntry entry : list) {
                if (entry.commitTS() == commitTS) {
                    storage.delete(MvccKey.encode(userKey, entry.startTS(),
                            commitTS, entry.writeType()));
                } else {
                    updated.add(entry);
                }
            }
            if (updated.isEmpty()) {
                index.remove(new ByteKey(userKey));
            } else {
                index.put(new ByteKey(userKey), updated);
            }
        }
    }

    /** 用户键全部版本（commitTS 升序，含 tombstone）。 */
    public List<MvccEntry> versions(byte[] userKey) {
        List<MvccEntry> list = index.get(new ByteKey(userKey));
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Snapshot 读：最大 commitTS <= readTS 的可见版本（DELETE 隐藏旧值）。 */
    public MvccEntry read(byte[] userKey, long readTS) {
        MvccEntry latest = null;
        for (MvccEntry entry : versions(userKey)) {
            if (entry.commitTS() <= readTS) {
                if (entry.isVisible()) {
                    latest = entry;
                }
            } else {
                break;
            }
        }
        return latest;
    }

    /** 最近已提交版本（Redis 默认读）。 */
    public byte[] latestValue(byte[] userKey) {
        List<MvccEntry> versions = versions(userKey);
        for (int i = versions.size() - 1; i >= 0; i--) {
            MvccEntry version = versions.get(i);
            if (version.writeType() == WriteType.LOCK) {
                continue; // provisional 不可见
            }
            return version.isDelete() ? null : version.value();
        }
        return null;
    }

    /** 范围扫描（Snapshot）：按 userKey 分组，readTS 可见值。 */
    public Map<byte[], byte[]> scan(byte[] startKey, byte[] endKey, long readTS) {
        Map<byte[], byte[]> result = new TreeMap<>(
                (a, b) -> java.util.Arrays.compareUnsigned(a, b));
        List<ByteKey> keys = new ArrayList<>(index.keySet());
        keys.sort((a, b) -> java.util.Arrays.compareUnsigned(a.key(), b.key()));
        for (ByteKey key : keys) {
            byte[] userKey = key.key();
            if (endKey != null
                    && java.util.Arrays.compareUnsigned(userKey, endKey) >= 0) {
                break;
            }
            if (java.util.Arrays.compareUnsigned(userKey, startKey) < 0) {
                continue;
            }
            MvccEntry visible = null;
            for (MvccEntry version : index.get(key)) {
                if (version.commitTS() <= readTS && version.isVisible()) {
                    visible = version;
                }
            }
            if (visible != null && !visible.isDelete()) {
                result.put(userKey, visible.value());
            }
        }
        return result;
    }

    public long versionCount() {
        synchronized (index) {
            return index.values().stream().mapToLong(List::size).sum();
        }
    }

    private static void flush(byte[] userKey, MvccEntry visible,
                              Map<byte[], byte[]> result) {
        if (userKey != null && visible != null && !visible.isDelete()) {
            result.put(userKey, visible.value());
        }
    }
}
