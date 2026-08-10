package io.tieringkv.mvcc;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * MVCC 存储适配器（ADR-0071）：底层 StorageEngine 保存
 * [userKey][commitTS] 版本；DELETE 以 tombstone 哨兵表示。
 */
public final class MvccStorageEngine {

    private final StorageEngine storage;

    public MvccStorageEngine(StorageEngine storage) {
        this.storage = storage;
    }

    public StorageEngine underlying() {
        return storage;
    }

    /** 写一个可见版本（PUT/DELETE）。 */
    public void putVersion(byte[] userKey, byte[] value, long startTS,
                           long commitTS, WriteType writeType) {
        storage.put(MvccKey.encode(userKey, startTS, commitTS, writeType),
                value == null ? new byte[0] : value);
    }

    /** 删除指定版本（rollback/GC）。 */
    public void deleteVersion(byte[] userKey, long commitTS) {
        for (MvccEntry entry : versions(userKey)) {
            if (entry.commitTS() == commitTS) {
                storage.delete(MvccKey.encode(userKey, entry.startTS(),
                        commitTS, entry.writeType()));
                return;
            }
        }
    }

    /** 用户键全部版本（commitTS 升序，含 tombstone）。 */
    public List<MvccEntry> versions(byte[] userKey) {
        List<MvccEntry> result = new ArrayList<>();
        try (StorageIterator iterator = storage.iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                if (!MvccKey.startsWith(entry.key(), userKey)) {
                    continue;
                }
                WriteType type = MvccKey.writeType(entry.key());
                result.add(new MvccEntry(userKey,
                        type == WriteType.DELETE ? null : entry.value(),
                        MvccKey.startTS(entry.key()),
                        MvccKey.commitTS(entry.key()), type));
            }
        }
        result.sort(Comparator.comparingLong(MvccEntry::commitTS));
        return result;
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
        if (versions.isEmpty()) {
            return null;
        }
        MvccEntry latest = versions.get(versions.size() - 1);
        return latest.isDelete() ? null : latest.value();
    }

    /** 范围扫描（Snapshot）：按 userKey 分组，readTS 可见值。 */
    public Map<byte[], byte[]> scan(byte[] startKey, byte[] endKey, long readTS) {
        Map<byte[], byte[]> result = new TreeMap<>(
                (a, b) -> java.util.Arrays.compareUnsigned(a, b));
        byte[] currentUser = null;
        MvccEntry currentVisible = null;
        try (StorageIterator iterator = storage.iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                byte[] userKey = MvccKey.userKey(entry.key());
                if (endKey != null
                        && java.util.Arrays.compareUnsigned(userKey, endKey) >= 0) {
                    break;
                }
                if (java.util.Arrays.compareUnsigned(userKey, startKey) < 0) {
                    continue;
                }
                if (!java.util.Arrays.equals(userKey, currentUser)) {
                    flush(currentUser, currentVisible, result);
                    currentUser = userKey;
                    currentVisible = null;
                }
                WriteType type = MvccKey.writeType(entry.key());
                MvccEntry version = new MvccEntry(userKey,
                        type == WriteType.DELETE ? null : entry.value(),
                        MvccKey.startTS(entry.key()),
                        MvccKey.commitTS(entry.key()), type);
                if (version.commitTS() <= readTS && version.isVisible()) {
                    currentVisible = version;
                }
            }
            flush(currentUser, currentVisible, result);
        }
        return result;
    }

    public long versionCount() {
        long count = 0;
        try (StorageIterator iterator = storage.iterator()) {
            while (iterator.hasNext()) {
                iterator.next();
                count++;
            }
        }
        return count;
    }

    private static void flush(byte[] userKey, MvccEntry visible,
                              Map<byte[], byte[]> result) {
        if (userKey != null && visible != null && !visible.isDelete()) {
            result.put(userKey, visible.value());
        }
    }
}
