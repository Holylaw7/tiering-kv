package io.tieringkv.storage.wal;

import java.io.IOException;
import java.util.List;

/** WAL 批量写入器（ADR-0048）：一组 WALEntry 一次段追加。 */
public final class BatchWriter {

    private BatchWriter() {
    }

    public static void append(WALWriter writer, List<WALEntry> entries) throws IOException {
        writer.appendBatch(entries);
    }
}
