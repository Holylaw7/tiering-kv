package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.observability.BackupMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataState;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 备份/恢复（ADR-0097）：元数据 + MVCC 索引闭环。 */
class BackupRestoreTest {

    @TempDir
    Path dir;

    @Test
    void backupRestoreMetadata() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.register("t1", new byte[]{1}, 1,
                Map.of("r1", List.of())));
        Path backup = dir.resolve("backup");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        BackupManager.backup(backup, state, engine);
        TransactionMetadataState restored =
                RestoreManager.restoreMetadata(backup);
        assertThat(restored.get("t1")).isNotNull();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void backupRestoreMvccIndex() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        Path backup = dir.resolve("backup-mvcc");
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        MvccStorageEngine restored = RestoreManager.restoreMvcc(
                backup, MemTable.create());
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        ((MemTable) engine.underlying()).close();
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void destroyAndRestoreTransactionReadable() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.register("t1", new byte[]{1}, 1,
                Map.of("r1", List.of())));
        state.apply(TxnMetaCommand.prepare("t1", 9));
        state.apply(TxnMetaCommand.commit("t1", 9));
        Path backup = dir.resolve("full-backup");
        BackupManager.backup(backup, state, engine);
        ((MemTable) engine.underlying()).close(); // destroy node
        MvccStorageEngine restored = RestoreManager.restoreMvcc(
                backup, MemTable.create());
        TransactionMetadataState meta = RestoreManager.restoreMetadata(backup);
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(meta.get("t1").state().name()).isEqualTo("COMMITTED");
        ((MemTable) restored.underlying()).close();
    }

    @Test
    void backupRestoreFeedMetricsRegistry() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        BackupMetricsRegistry metrics = new BackupMetricsRegistry();
        Path backup = dir.resolve("metric-backup");
        BackupManager.backup(backup, new TransactionMetadataState(),
                engine, metrics);
        assertThat(metrics.snapshot().backups()).isEqualTo(1);
        assertThat(metrics.snapshot().backupBytes()).isPositive();

        RestoreManager.restoreMetadata(backup, metrics);
        RestoreManager.restoreMvcc(backup, MemTable.create(), metrics);
        assertThat(metrics.snapshot().restores()).isEqualTo(2);
        assertThat(metrics.snapshot().restoreBytes()).isPositive();
        ((MemTable) engine.underlying()).close();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50, 100, 200, 500})
    void parameterizedBackupRestore(int keyCount) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < keyCount; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        Path backup = dir.resolve("backup-" + keyCount);
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        MvccStorageEngine restored = RestoreManager.restoreMvcc(
                backup, MemTable.create());
        for (int i = 0; i < keyCount; i++) {
            assertThat(restored.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
        ((MemTable) engine.underlying()).close();
        ((MemTable) restored.underlying()).close();
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 3, 5, 10, 20})
    void parameterizedMetadataBackup(int txnCount) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < txnCount; i++) {
            state.apply(TxnMetaCommand.register("t" + i, new byte[]{1},
                    i, Map.of("r1", List.of())));
        }
        Path backup = dir.resolve("meta-" + txnCount);
        BackupManager.backup(backup, state,
                new MvccStorageEngine(MemTable.create()));
        assertThat(RestoreManager.restoreMetadata(backup).size())
                .isEqualTo(txnCount);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
