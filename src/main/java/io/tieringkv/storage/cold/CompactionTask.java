package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 全量合并任务（ADR-0019）：latest-wins；tombstone 与过期 TTL 直接丢弃。 */
public final class CompactionTask {

    private final ColdStorageEngine.Config config;
    private final List<SSTableMeta> inputs;
    private final long outputId;

    public CompactionTask(ColdStorageEngine.Config config, List<SSTableMeta> inputs, long outputId) {
        this.config = config;
        this.inputs = inputs;
        this.outputId = outputId;
    }

    public SSTableMeta run() throws IOException {
        long expectedEntries = inputs.stream().mapToLong(SSTableMeta::entryCount).sum();
        List<SSTableReader> readers = new ArrayList<>(inputs.size());
        try {
            List<MergingIterator.Source> sources = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) {
                SSTableReader reader = SSTableReader.open(inputs.get(i), config.directory());
                readers.add(reader);
                sources.add(new MergingIterator.Source(reader.iterator(), i));
            }
            MergingIterator merged = new MergingIterator(sources);
            long now = System.currentTimeMillis();
            try (SSTableWriter writer = new SSTableWriter(config.directory(), outputId,
                    (int) Math.min(expectedEntries, Integer.MAX_VALUE),
                    config.bloomBitsPerKey(), config.blockTargetBytes())) {
                while (merged.hasNext()) {
                    KeyValueEntry entry = merged.next();
                    if (entry.deleted() || entry.isExpired(now)) {
                        continue; // tombstone 移除键；过期 TTL 丢弃
                    }
                    writer.writeEntry(entry);
                }
                return writer.finish();
            }
        } finally {
            for (SSTableReader reader : readers) {
                reader.close();
            }
        }
    }
}
