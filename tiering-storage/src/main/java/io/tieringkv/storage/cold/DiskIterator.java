package io.tieringkv.storage.cold;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.List;

/** 单表顺序迭代（ADR-0018）：按块序读取并解码条目。 */
public final class DiskIterator implements StorageIterator {

    private final SSTableReader reader;
    private final List<BlockIndex.IndexEntry> blocks;
    private int blockIndex;
    private List<KeyValueEntry> currentBlock;
    private int entryIndex;
    private boolean exhausted;

    public DiskIterator(SSTableReader reader, List<BlockIndex.IndexEntry> blocks) {
        this.reader = reader;
        this.blocks = blocks;
    }

    @Override
    public boolean hasNext() {
        if (exhausted) {
            return false;
        }
        while (currentBlock == null || entryIndex >= currentBlock.size()) {
            if (blockIndex >= blocks.size()) {
                exhausted = true;
                return false;
            }
            try {
                BlockIndex.IndexEntry blockEntry = blocks.get(blockIndex++);
                currentBlock = Block.decode(reader.readBlockBuffer(blockEntry));
                entryIndex = 0;
            } catch (java.io.IOException e) {
                throw new ColdCorruptionException("read block failed: " + e.getMessage());
            }
        }
        return true;
    }

    @Override
    public KeyValueEntry next() {
        if (!hasNext()) {
            throw new IllegalStateException("no more entries");
        }
        return currentBlock.get(entryIndex++);
    }

    @Override
    public void close() {
        // reader 由 ColdStorageEngine 统一管理
    }
}
