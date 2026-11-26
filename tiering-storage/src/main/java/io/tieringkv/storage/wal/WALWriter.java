package io.tieringkv.storage.wal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * WAL 写入器（ADR-0014 / B3 Group Commit 优化）：负责记录编码、批量段追加与 fsync 策略执行。
 */
public final class WALWriter implements AutoCloseable {

    private final SegmentManager segments;
    private volatile WALConfig.FsyncPolicy fsyncPolicy;
    private long lastForceMillis;
    /** 自上次 force 以来是否有新写入（TD-106）：空闲时跳过无意义的 fsync。 */
    private volatile boolean dirty;

    public WALWriter(SegmentManager segments, WALConfig.FsyncPolicy fsyncPolicy) {
        this.segments = segments;
        this.fsyncPolicy = fsyncPolicy;
    }

    public WALConfig.FsyncPolicy fsyncPolicy() {
        return fsyncPolicy;
    }

    public void setFsyncPolicy(WALConfig.FsyncPolicy fsyncPolicy) {
        if (fsyncPolicy == null) {
            throw new IllegalArgumentException("fsyncPolicy required");
        }
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

    /**
     * 预编码帧批量追加（B3 Group Commit 专用）：调用方已在锁外并行完成序列化与 CRC32C，
     * 此处连续追加全部帧并仅执行单次 fsync 决策（ALWAYS 下整批共享 1 次 FileChannel.force）。
     */
    void appendEncodedBatch(List<byte[]> encodedRecords) throws IOException {
        if (encodedRecords.isEmpty()) {
            return;
        }
        LogSegment segment = segments.current();
        for (int i = 0; i < encodedRecords.size(); i++) {
            segment.append(encodedRecords.get(i));
        }
        applyFsyncAfterAppend(segment);
    }

    private void appendRecord(byte[] record) throws IOException {
        LogSegment segment = segments.current();
        segment.append(record);
        applyFsyncAfterAppend(segment);
    }

    private void applyFsyncAfterAppend(LogSegment segment) throws IOException {
        dirty = true;
        WALConfig.FsyncPolicy policy = this.fsyncPolicy;
        if (policy == WALConfig.FsyncPolicy.ALWAYS) {
            segment.force();
            dirty = false;
        } else if (policy == WALConfig.FsyncPolicy.EVERY_SEC) {
            long now = System.currentTimeMillis();
            if (now - lastForceMillis >= 1000) {
                segment.force();
                dirty = false;
                lastForceMillis = now;
            }
        }
    }

    /** 是否有未落盘的写入（供后台调度判断是否需要 force）。 */
    public boolean hasPendingWrites() {
        return dirty;
    }

    public void force() throws IOException {
        if (!dirty) {
            return; // 空闲期不做无意义的 flush + fsync
        }
        segments.current().force();
        dirty = false;
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
