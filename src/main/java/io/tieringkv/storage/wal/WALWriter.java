package io.tieringkv.storage.wal;

import java.io.IOException;

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
