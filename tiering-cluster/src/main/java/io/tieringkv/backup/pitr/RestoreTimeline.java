package io.tieringkv.backup.pitr;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.storage.StorageEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 时间线恢复（ADR-0104）：快照 + 水位后归档日志重放到目标时间。 */
public final class RestoreTimeline {

    private RestoreTimeline() {
    }

    public static MvccStorageEngine restore(StorageEngine storage,
                                            Path checkpointDir,
                                            Path archiveDir,
                                            long targetCommitTS)
            throws IOException {
        CheckpointManager.Checkpoint checkpoint =
                CheckpointManager.load(checkpointDir);
        MvccStorageEngine engine = restoreSnapshot(storage,
                checkpoint.snapshotBytes());
        List<PitrRecord> records =
                WALArchiveManager.open(archiveDir).readAfter(
                        checkpoint.watermark());
        for (PitrRecord record : records) {
            if (record.commitTS() > targetCommitTS) {
                continue;
            }
            engine.putVersion(record.key(), record.value(),
                    record.startTS(), record.commitTS(),
                    record.deleted() ? WriteType.DELETE : WriteType.PUT);
        }
        return engine;
    }

    private static MvccStorageEngine restoreSnapshot(StorageEngine storage,
                                                     byte[] snapshot)
            throws IOException {
        return PersistentMvccIndex.restoreBytes(snapshot, storage);
    }
}
