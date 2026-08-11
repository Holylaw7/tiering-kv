package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.txn.meta.MetadataSnapshotManager;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.io.IOException;
import java.nio.file.Path;

/** 恢复管理器（ADR-0097）：从备份恢复元数据与 MVCC 索引。 */
public final class RestoreManager {

    public static TransactionMetadataState restoreMetadata(Path backupDir)
            throws IOException {
        return MetadataSnapshotManager.load(backupDir.resolve("txn-meta.snap"));
    }

    public static MvccStorageEngine restoreMvcc(Path backupDir,
                                                StorageEngine storage)
            throws IOException {
        return PersistentMvccIndex.restore(
                backupDir.resolve("mvcc.index"), storage);
    }
}
