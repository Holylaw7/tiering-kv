package io.tieringkv.backup.pitr;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** WAL 归档管理器（ADR-0104）：变更日志写入、滚动与按水位读取。 */
public final class WALArchiveManager {

    private final PitrWriteLog log;

    private WALArchiveManager(PitrWriteLog log) {
        this.log = log;
    }

    public static WALArchiveManager open(Path dir) throws IOException {
        return new WALArchiveManager(PitrWriteLog.open(dir));
    }

    public static WALArchiveManager open(Path dir, int maxRecords)
            throws IOException {
        return new WALArchiveManager(PitrWriteLog.open(dir, maxRecords));
    }

    public long append(PitrRecord record) throws IOException {
        return log.append(record);
    }

    public long watermark() {
        return log.watermark();
    }

    public List<PitrRecord> readAll() throws IOException {
        return log.readAll();
    }

    public List<PitrRecord> readAfter(long watermark) throws IOException {
        return log.readAll().stream()
                .filter(record -> record.seq() > watermark)
                .toList();
    }

    public Path dir() {
        return log.dir();
    }
}
