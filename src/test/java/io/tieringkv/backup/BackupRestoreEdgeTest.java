package io.tieringkv.backup;

import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.lifecycle.TxnLifecycleState;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.metadata.TransactionMetadataState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 备份/恢复边界（ADR-0097）：全状态矩阵、损坏、缺文件、幂等。 */
class BackupRestoreEdgeTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "state {0}")
    @EnumSource(TxnMetaEntry.State.class)
    void entryStatesRoundTrip(TxnMetaEntry.State target) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.register("t1", new byte[]{1}, 1,
                Map.of("r1", List.of())));
        if (target == TxnMetaEntry.State.PREPARED
                || target == TxnMetaEntry.State.COMMITTED) {
            state.apply(TxnMetaCommand.prepare("t1", 42));
        }
        if (target == TxnMetaEntry.State.COMMITTED) {
            state.apply(TxnMetaCommand.commit("t1", 42));
        }
        if (target == TxnMetaEntry.State.ROLLED_BACK) {
            state.apply(TxnMetaCommand.rollback("t1"));
        }
        Path backup = dir.resolve("state-" + target);
        BackupManager.backup(backup, state,
                new MvccStorageEngine(MemTable.create()));
        TxnMetaEntry restored =
                RestoreManager.restoreMetadata(backup).get("t1");
        assertThat(restored.state()).isEqualTo(target);
        assertThat(restored.commitTS()).isEqualTo(
                target == TxnMetaEntry.State.PREPARED
                        || target == TxnMetaEntry.State.COMMITTED ? 42 : 0);
        closeEngines(restoreMvcc(backup));
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 10, 100, 500})
    void parameterizedKeyCounts(int keyCount) throws Exception {
        MvccStorageEngine engine = engine();
        for (int i = 0; i < keyCount; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        Path backup = dir.resolve("keys-" + keyCount);
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        MvccStorageEngine restored = restoreMvcc(backup);
        for (int i = 0; i < keyCount; i++) {
            assertThat(restored.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
        closeEngines(engine, restored);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 25})
    void parameterizedTxnCounts(int txnCount) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < txnCount; i++) {
            state.apply(TxnMetaCommand.register("t" + i, new byte[]{1},
                    i, Map.of("r1", List.of())));
        }
        Path backup = dir.resolve("txns-" + txnCount);
        BackupManager.backup(backup, state,
                new MvccStorageEngine(MemTable.create()));
        assertThat(RestoreManager.restoreMetadata(backup).size())
                .isEqualTo(txnCount);
    }

    @Test
    void lifecycleRecordsRoundTrip() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.lifecycle("t1", 1,
                TxnLifecycleState.ACTIVE.name(), 99_999));
        Path backup = dir.resolve("lifecycle");
        BackupManager.backup(backup, state,
                new MvccStorageEngine(MemTable.create()));
        assertThat(RestoreManager.restoreMetadata(backup)
                .lifecycleSnapshot().get("t1").expireAtMillis())
                .isEqualTo(99_999);
    }

    @Test
    void emptyBackupRoundTrip() throws Exception {
        Path backup = dir.resolve("empty");
        BackupManager.backup(backup, new TransactionMetadataState(),
                new MvccStorageEngine(MemTable.create()));
        assertThat(RestoreManager.restoreMetadata(backup).size()).isZero();
    }

    @Test
    void missingSnapshotReturnsEmpty() throws Exception {
        Path backup = dir.resolve("missing");
        Files.createDirectories(backup);
        assertThat(RestoreManager.restoreMetadata(backup).size()).isZero();
    }

    @Test
    void missingMvccIndexFailsFast() throws Exception {
        Path backup = dir.resolve("no-mvcc");
        Files.createDirectories(backup);
        assertThatThrownBy(() -> RestoreManager.restoreMvcc(
                backup, MemTable.create())).isInstanceOf(IOException.class);
    }

    @Test
    void destroyedCommittedTransactionRestored() throws Exception {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.register("t1", new byte[]{1}, 1,
                Map.of("r1", List.of())));
        state.apply(TxnMetaCommand.prepare("t1", 9));
        state.apply(TxnMetaCommand.commit("t1", 9));
        Path backup = dir.resolve("committed");
        BackupManager.backup(backup, state, engine);
        MvccStorageEngine restored = restoreMvcc(backup);
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(RestoreManager.restoreMetadata(backup).get("t1").state())
                .isEqualTo(TxnMetaEntry.State.COMMITTED);
        closeEngines(engine, restored);
    }

    @Test
    void destroyedRolledBackTransactionRestored() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        state.apply(TxnMetaCommand.register("t1", new byte[]{1}, 1,
                Map.of("r1", List.of())));
        state.apply(TxnMetaCommand.rollback("t1"));
        Path backup = dir.resolve("rolled-back");
        BackupManager.backup(backup, state,
                new MvccStorageEngine(MemTable.create()));
        assertThat(RestoreManager.restoreMetadata(backup).get("t1").state())
                .isEqualTo(TxnMetaEntry.State.ROLLED_BACK);
    }

    @Test
    void tombstoneRestored() throws Exception {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), null, 2, 20, WriteType.DELETE);
        Path backup = dir.resolve("tombstone");
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        MvccStorageEngine restored = restoreMvcc(backup);
        assertThat(restored.latestValue(bytes("k"))).isNull();
        closeEngines(engine, restored);
    }

    @Test
    void multipleVersionsRestoredLatestWins() throws Exception {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        Path backup = dir.resolve("versions");
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        MvccStorageEngine restored = restoreMvcc(backup);
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v2"));
        closeEngines(engine, restored);
    }

    @Test
    void backupTwiceReflectsLatest() throws Exception {
        MvccStorageEngine engine = engine();
        engine.putVersion(bytes("k"), bytes("v1"), 1, 10, WriteType.PUT);
        Path backup = dir.resolve("twice");
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        engine.putVersion(bytes("k"), bytes("v2"), 2, 20, WriteType.PUT);
        BackupManager.backup(backup, new TransactionMetadataState(), engine);
        MvccStorageEngine restored = restoreMvcc(backup);
        assertThat(restored.latestValue(bytes("k"))).isEqualTo(bytes("v2"));
        closeEngines(engine, restored);
    }

    private static MvccStorageEngine engine() {
        return new MvccStorageEngine(MemTable.create());
    }

    private static MvccStorageEngine restoreMvcc(Path backup)
            throws Exception {
        return RestoreManager.restoreMvcc(backup, MemTable.create());
    }

    private static void closeEngines(MvccStorageEngine... engines) {
        for (MvccStorageEngine engine : engines) {
            ((MemTable) engine.underlying()).close();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
