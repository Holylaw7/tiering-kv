package io.tieringkv.storage.cold;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MemTable Flush（ADR-0017）：快照 → SSTable → 版本守卫移除内存 → WAL checkpoint。
 * 快照期间不阻塞写；快照后变更的键保留在内存（版本不一致）。
 */
public final class FlushManager {

    private FlushManager() {
    }

    public record FlushStats(long entriesFlushed, long bytesFlushed, long entriesRemaining) {
    }

    public static FlushStats flush(MemTable memTable, WALManager wal, ColdStorageEngine cold)
            throws IOException {
        if (wal != null) {
            return wal.withBackgroundTierReadLock(() -> flushInternal(memTable, wal, cold));
        }
        return flushInternal(memTable, null, cold);
    }

    private static FlushStats flushInternal(MemTable memTable, WALManager wal, ColdStorageEngine cold)
            throws IOException {
        List<KeyValueEntry> snapshot = new ArrayList<>();
        try (StorageIterator iterator = memTable.iterator()) {
            while (iterator.hasNext()) {
                snapshot.add(iterator.next());
            }
        }
        if (snapshot.isEmpty()) {
            return new FlushStats(0, 0, memTable.size());
        }
        cold.writeTable(snapshot);
        long bytesFlushed = 0;
        for (KeyValueEntry entry : snapshot) {
            bytesFlushed += entry.size();
        }
        long removed = 0;
        for (KeyValueEntry entry : snapshot) {
            if (memTable.removePhysicalIfVersion(entry.key(), entry.version())) {
                removed++;
            } else {
                // Flush 期间若该键被并发删除为 tombstone，需同步在冷层写入墓碑防止旧值复活
                KeyValueEntry latest = memTable.getEntry(entry.key());
                if (latest != null && latest.deleted()) {
                    cold.delete(entry.key());
                }
            }
        }
        if (wal != null) {
            wal.checkpoint(memTable);
        }
        return new FlushStats(snapshot.size(), bytesFlushed, memTable.size());
    }
}
