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

    public record FlushStats(long entriesFlushed, long entriesRemaining) {
    }

    public static FlushStats flush(MemTable memTable, WALManager wal, ColdStorageEngine cold)
            throws IOException {
        List<KeyValueEntry> snapshot = new ArrayList<>();
        try (StorageIterator iterator = memTable.iterator()) {
            while (iterator.hasNext()) {
                snapshot.add(iterator.next());
            }
        }
        if (snapshot.isEmpty()) {
            return new FlushStats(0, memTable.size());
        }
        cold.writeTable(snapshot);
        long removed = 0;
        for (KeyValueEntry entry : snapshot) {
            if (memTable.removePhysicalIfVersion(entry.key(), entry.version())) {
                removed++;
            }
        }
        wal.checkpoint(memTable);
        return new FlushStats(snapshot.size(), memTable.size());
    }
}
