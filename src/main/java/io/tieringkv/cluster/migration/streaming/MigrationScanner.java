package io.tieringkv.cluster.migration.streaming;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.Arrays;

/**
 * 流式扫描器（ADR-0053）：单次有序迭代，按 slot 范围、游标 lastKey
 * 与版本屏障过滤；只迁移 version <= barrier 的条目。
 */
public final class MigrationScanner {

    private final StorageIterator iterator;
    private final int slotStart;
    private final int slotEnd;
    private final long versionBarrier;
    private final byte[] cursorKey;
    private final boolean fullRange;
    private KeyValueEntry next;

    public MigrationScanner(StorageIterator iterator, int slotStart, int slotEnd,
                            long versionBarrier, byte[] cursorKey) {
        this.iterator = iterator;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.versionBarrier = versionBarrier;
        this.cursorKey = cursorKey == null ? new byte[0] : cursorKey.clone();
        this.fullRange = slotStart <= 0 && slotEnd >= HashSlotRouter.SLOT_COUNT - 1;
        advance();
    }

    public boolean hasNext() {
        return next != null;
    }

    public KeyValueEntry next() {
        KeyValueEntry current = next;
        advance();
        return current;
    }

    private void advance() {
        while (iterator.hasNext()) {
            KeyValueEntry entry = iterator.next();
            if ((!fullRange && !inRange(entry.key()))
                    || compare(entry.key(), cursorKey) <= 0
                    || entry.version() > versionBarrier) {
                continue;
            }
            next = entry;
            return;
        }
        next = null;
    }

    private boolean inRange(byte[] key) {
        int slot = HashSlotRouter.slot(key);
        return slot >= slotStart && slot <= slotEnd;
    }

    private static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }
}
