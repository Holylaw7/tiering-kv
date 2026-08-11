package io.tieringkv.backup.pitr;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;

import java.io.IOException;
import java.nio.file.Path;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** MVCC 写入旁路记录器（ADR-0104）：已提交版本 → PITR 归档日志。 */
public final class MvccPitrRecorder {

    private final MvccStorageEngine engine;
    private final WALArchiveManager archive;
    private final AtomicLong seq;
    private volatile String regionId = "r1";
    private volatile String txnId = "pitr";

    public MvccPitrRecorder(MvccStorageEngine engine, Path archiveDir)
            throws IOException {
        this.engine = engine;
        this.archive = WALArchiveManager.open(archiveDir);
        this.seq = new AtomicLong(archive.watermark() + 1);
    }

    /** 同步分配 seq + 追加：并发写者共享 recorder 时顺序保持。 */
    public synchronized long record(long startTS, long commitTS,
                                    byte[] key, byte[] value,
                                    boolean deleted) throws IOException {
        long current = seq.getAndIncrement();
        archive.append(new PitrRecord(current, startTS, commitTS, key,
                value, deleted, txnId, regionId));
        return current;
    }

    public void putVersion(byte[] key, byte[] value, long startTS,
                           long commitTS, WriteType writeType)
            throws IOException {
        boolean deleted = writeType == WriteType.DELETE;
        record(startTS, commitTS, key, deleted ? null : value, deleted);
        engine.putVersion(key, value, startTS, commitTS, writeType);
    }

    public MvccStorageEngine engine() {
        return engine;
    }

    public long watermark() {
        return seq.get() - 1;
    }

    public void context(String txnId, String regionId) {
        this.txnId = txnId;
        this.regionId = regionId;
    }
}
