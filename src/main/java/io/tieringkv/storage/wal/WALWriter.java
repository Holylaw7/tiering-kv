package io.tieringkv.storage.wal;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * WAL 写入器（ADR-0014）：负责记录编码、段内追加与 fsync 策略执行。
 */
public final class WALWriter implements AutoCloseable {

    private final SegmentManager segments;
    private final WALConfig.FsyncPolicy fsyncPolicy;
    private long lastForceMillis;

    public WALWriter(SegmentManager segments, WALConfig.FsyncPolicy fsyncPolicy) {
        this.segments = segments;
        this.fsyncPolicy = fsyncPolicy;
    }

    public void append(WALEntry entry) throws IOException {
        byte[] record = WALRecord.encode(entry);
        appendRecord(record);
    }

    /** 批量追加（ADR-0048）：N 条编码记录一次段追加（单锁/单 fsync 决策）。 */
    public void appendBatch(List<WALEntry> entries) throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (WALEntry entry : entries) {
            out.write(WALRecord.encode(entry));
        }
        appendRecord(out.toByteArray());
    }

    private void appendRecord(byte[] record) throws IOException {
        LogSegment segment = segments.current();
        segment.append(record);
        if (fsyncPolicy == WALConfig.FsyncPolicy.ALWAYS) {
            segment.force();
        } else if (fsyncPolicy == WALConfig.FsyncPolicy.EVERY_SEC) {
            long now = System.currentTimeMillis();
            if (now - lastForceMillis >= 1000) {
                segment.force();
                lastForceMillis = now;
            }
        }
    }

    public void force() throws IOException {
        segments.current().force();
        lastForceMillis = System.currentTimeMillis();
    }

    public void rotateIfNeeded(long maxSegmentBytes) throws IOException {
        if (segments.current().size() >= maxSegmentBytes) {
            segments.rotate();
        }
    }

    public long currentSize() {
        return segments.current().size();
    }

    public long currentSequence() {
        return segments.current().sequence();
    }

    @Override
    public void close() throws IOException {
        segments.close();
    }
}
