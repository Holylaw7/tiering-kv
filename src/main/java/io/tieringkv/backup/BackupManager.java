package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.observability.BackupMetricsRegistry;
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
        return backup(backupDir, metadata, engine, null);
    }

    /** 可观测性收口（ADR-0344）：可选备份指标注册表（additive）。 */
    public static Path backup(Path backupDir,
                              TransactionMetadataState metadata,
                              MvccStorageEngine engine,
                              BackupMetricsRegistry metrics)
            throws IOException {
        Files.createDirectories(backupDir);
        Path meta = backupDir.resolve("txn-meta.snap");
        Path mvcc = backupDir.resolve("mvcc.index");
        MetadataSnapshotManager.snapshot(meta, metadata);
        PersistentMvccIndex.save(mvcc,
                PersistentMvccIndex.snapshot(engine));
        if (metrics != null) {
            metrics.recordBackup(Files.size(meta) + Files.size(mvcc));
        }
        return backupDir;
    }
}
