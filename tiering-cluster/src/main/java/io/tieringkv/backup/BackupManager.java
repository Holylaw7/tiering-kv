package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.observability.BackupMetricsRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;
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
        return backup(backupDir, metadata, engine, metrics, null);
    }

    /** 向量索引纳入备份（ADR-0344 收口）：可选 VectorIndexStore。 */
    public static Path backup(Path backupDir,
                              TransactionMetadataState metadata,
                              MvccStorageEngine engine,
                              BackupMetricsRegistry metrics,
                              VectorIndexStore vectorStore)
            throws IOException {
        Files.createDirectories(backupDir);
        Path meta = backupDir.resolve("txn-meta.snap");
        Path mvcc = backupDir.resolve("mvcc.index");
        Path vector = backupDir.resolve("vector.idx");
        MetadataSnapshotManager.snapshot(meta, metadata);
        PersistentMvccIndex.save(mvcc,
                PersistentMvccIndex.snapshot(engine));
        if (vectorStore != null) {
            vectorStore.checkpoint(vector);
        }
        if (metrics != null) {
            long bytes = Files.size(meta) + Files.size(mvcc);
            if (vectorStore != null) {
                bytes += Files.size(vector);
            }
            metrics.recordBackup(bytes);
        }
        return backupDir;
    }
}
