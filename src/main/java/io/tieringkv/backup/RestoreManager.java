package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.observability.BackupMetricsRegistry;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.txn.meta.MetadataSnapshotManager;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 恢复管理器（ADR-0097）：从备份恢复元数据与 MVCC 索引。 */
public final class RestoreManager {

    public static TransactionMetadataState restoreMetadata(Path backupDir)
            throws IOException {
        return restoreMetadata(backupDir, null);
    }

    /** 可观测性收口（ADR-0344）：可选指标注册表（additive）。 */
    public static TransactionMetadataState restoreMetadata(
            Path backupDir, BackupMetricsRegistry metrics)
            throws IOException {
        Path file = backupDir.resolve("txn-meta.snap");
        TransactionMetadataState state = MetadataSnapshotManager.load(file);
        if (metrics != null) {
            metrics.recordRestore(Files.size(file));
        }
        return state;
    }

    public static MvccStorageEngine restoreMvcc(Path backupDir,
                                                StorageEngine storage)
            throws IOException {
        return restoreMvcc(backupDir, storage, null);
    }

    /** 可观测性收口（ADR-0344）：可选指标注册表（additive）。 */
    public static MvccStorageEngine restoreMvcc(Path backupDir,
                                                StorageEngine storage,
                                                BackupMetricsRegistry metrics)
            throws IOException {
        Path file = backupDir.resolve("mvcc.index");
        MvccStorageEngine engine = PersistentMvccIndex.restore(file, storage);
        if (metrics != null) {
            metrics.recordRestore(Files.size(file));
        }
        return engine;
    }
}
