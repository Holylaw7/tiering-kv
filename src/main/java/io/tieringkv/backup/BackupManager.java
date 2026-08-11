package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.txn.meta.MetadataSnapshotManager;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 备份管理器（ADR-0097）：元数据快照 + MVCC 索引。 */
public final class BackupManager {

    public static Path backup(Path backupDir,
                              TransactionMetadataState metadata,
                              MvccStorageEngine engine) throws IOException {
        Files.createDirectories(backupDir);
        Path meta = backupDir.resolve("txn-meta.snap");
        Path mvcc = backupDir.resolve("mvcc.index");
        MetadataSnapshotManager.snapshot(meta, metadata);
        PersistentMvccIndex.save(mvcc,
                PersistentMvccIndex.snapshot(engine));
        return backupDir;
    }
}
