package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 事务日志重放（ADR-0081）：编码 / 持久化 / 幂等 / 损坏容忍。 */
class TxnReplayTest {

    @TempDir
    Path dir;

    @Test
    void encodeDecodeRoundTrip() {
        TxnStateRecord record = record("t1", TxnStateRecord.State.COMMIT,
                10, 20, List.of(mut("k", "v", false)));
        TxnStateRecord decoded = PersistentTxnJournal.decode(
                PersistentTxnJournal.encode(record));
        assertThat(decoded.txnId()).isEqualTo("t1");
        assertThat(decoded.state()).isEqualTo(TxnStateRecord.State.COMMIT);
        assertThat(decoded.startTS()).isEqualTo(10);
        assertThat(decoded.commitTS()).isEqualTo(20);
        assertThat(decoded.mutations()).hasSize(1);
        assertThat(decoded.mutations().get(0).key()).isEqualTo(bytes("k"));
    }

    @Test
    void recordStateAppendsToFile() throws Exception {
        try (PersistentTxnJournal journal = journal()) {
            journal.recordState(record("t1",
                    TxnStateRecord.State.PREWRITE, 1, 0,
                    List.of(mut("k", "v", false)))).join();
            journal.recordState(record("t1",
                    TxnStateRecord.State.COMMIT, 1, 2,
                    List.of(mut("k", "v", false)))).join();
            assertThat(journal.size()).isEqualTo(2);
        }
    }

    @Test
    void replayPrewriteOnlyDoesNotCommit() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void replayCommitCompletesCommit() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        prewrite(fixture, "t1", "k", "v");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k", "v", false)))).join();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @Test
    void replayCommitWithDelete() throws Exception {
        Fixture fixture = fixture();
        fixture.engine.putVersion(bytes("k"), bytes("old"), 0, 1,
                WriteType.PUT);
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 5, 0,
                List.of(mut("k", null, true)))).join();
        prewrite(fixture, "t1", "k", null);
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 5, 6,
                List.of(mut("k", null, true)))).join();
        new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal);
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void replayRollbackCleansLocks() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        prewrite(fixture, "t1", "k", "v");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.ROLLBACK, 1, 0,
                List.of(mut("k", "v", false)))).join();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(fixture.locks.size()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void replayIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        prewrite(fixture, "t1", "k", "v");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k", "v", false)))).join();
        TxnRecoveryReplay replay =
                new TxnRecoveryReplay(fixture.engine, fixture.locks);
        assertThat(replay.replay(fixture.journal).committed()).isEqualTo(1);
        assertThat(replay.replay(fixture.journal).committed()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void corruptedTailTolerated() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k", "v", false)))).join();
        fixture.close();
        // 追加半个记录（模拟崩溃写一半）
        Files.write(fixture.path, new byte[]{0, 0, 0, 30, 1, 2, 3},
                java.nio.file.StandardOpenOption.APPEND);
        try (PersistentTxnJournal reopened = journalAt(fixture.path)) {
            assertThat(reopened.replay()).hasSize(1);
        }
    }

    @Test
    void corruptedMiddleThrows() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        fixture.journal.recordState(record("t2",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k2", "v", false)))).join();
        fixture.close();
        byte[] data = Files.readAllBytes(fixture.path);
        data[data.length / 2] ^= 0x7F;
        Files.write(fixture.path, data);
        try (PersistentTxnJournal reopened = journalAt(fixture.path)) {
            assertThatThrownBy(reopened::replay)
                    .isInstanceOf(IOException.class);
        }
    }

    @Test
    void emptyJournalReplayEmpty() throws Exception {
        Fixture fixture = fixture();
        assertThat(new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal).skipped()).isZero();
        fixture.close();
    }

    @Test
    void multiMutationCommit() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("a", "1", false), mut("b", "2", false)))).join();
        prewrite(fixture, "t1", "a", "1");
        prewrite(fixture, "t1", "b", "2");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("a", "1", false), mut("b", "2", false)))).join();
        new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal);
        assertThat(fixture.engine.latestValue(bytes("a"))).isEqualTo(bytes("1"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isEqualTo(bytes("2"));
        fixture.close();
    }

    @Test
    void commitAfterPartialPrewriteSkipsMissingLocks() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("a", "1", false), mut("b", "2", false)))).join();
        prewrite(fixture, "t1", "a", "1"); // b 未 prewrite（崩溃中断）
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("a", "1", false), mut("b", "2", false)))).join();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("a"))).isEqualTo(bytes("1"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void journalSurvivesReopen() throws Exception {
        Path file = dir.resolve("txn.log");
        try (PersistentTxnJournal journal =
                     new PersistentTxnJournal(file, new TxnJournal.InMemory())) {
            journal.recordState(record("t1",
                    TxnStateRecord.State.PREWRITE, 1, 0,
                    List.of(mut("k", "v", false)))).join();
        }
        try (PersistentTxnJournal reopened =
                     new PersistentTxnJournal(file, new TxnJournal.InMemory())) {
            assertThat(reopened.replay()).hasSize(1);
        }
    }

    @Test
    void rollbackAfterCommitIgnored() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        prewrite(fixture, "t1", "k", "v");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k", "v", false)))).join();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.ROLLBACK, 1, 0,
                List.of(mut("k", "v", false)))).join();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(result.rolledBack()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void invalidMagicThrows() throws Exception {
        Path file = dir.resolve("bad.log");
        Files.write(file, new byte[]{1, 2, 3, 4, 5, 6, 0, 0, 0, 1});
        try (PersistentTxnJournal journal =
                     new PersistentTxnJournal(file, new TxnJournal.InMemory())) {
            assertThatThrownBy(journal::replay).isInstanceOf(IOException.class);
        }
    }

    @Test
    void metricsRecordedOnRecovery() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        prewrite(fixture, "t1", "k", "v");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k", "v", false)))).join();
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        new TxnRecoveryReplay(fixture.engine, fixture.locks, metrics)
                .replay(fixture.journal);
        assertThat(metrics.snapshot().recoveryTxn()).isEqualTo(1);
        fixture.close();
    }

    @Test
    void multipleTransactionsReplayIndependently() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("a", "1", false)))).join();
        prewrite(fixture, "t1", "a", "1");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("a", "1", false)))).join();
        fixture.journal.recordState(record("t2",
                TxnStateRecord.State.PREWRITE, 3, 0,
                List.of(mut("b", "2", false)))).join();
        prewrite(fixture, "t2", "b", "2");
        fixture.journal.recordState(record("t2",
                TxnStateRecord.State.ROLLBACK, 3, 0,
                List.of(mut("b", "2", false)))).join();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("a"))).isEqualTo(bytes("1"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @Test
    void recoveryLeavesTerminalState() throws Exception {
        Fixture fixture = fixture();
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.PREWRITE, 1, 0,
                List.of(mut("k", "v", false)))).join();
        prewrite(fixture, "t1", "k", "v");
        fixture.journal.recordState(record("t1",
                TxnStateRecord.State.COMMIT, 1, 2,
                List.of(mut("k", "v", false)))).join();
        new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal);
        assertThat(fixture.locks.size()).isZero();
        assertThat(fixture.engine.versions(bytes("k"))).isNotEmpty();
        fixture.close();
    }

    @Test
    void emptyMutationsRecordRoundTrip() {
        TxnStateRecord record = record("t1", TxnStateRecord.State.PREWRITE,
                1, 0, List.of());
        TxnStateRecord decoded = PersistentTxnJournal.decode(
                PersistentTxnJournal.encode(record));
        assertThat(decoded.txnId()).isEqualTo("t1");
        assertThat(decoded.mutations()).isEmpty();
        assertThat(decoded.primary()).isEmpty();
    }

    // ---------- helpers ----------

    private Fixture fixture() throws Exception {
        Path file = dir.resolve("txn-" + System.nanoTime() + ".log");
        PersistentTxnJournal journal = new PersistentTxnJournal(
                file, new TxnJournal.InMemory());
        return new Fixture(new MvccStorageEngine(MemTable.create()),
                new LockTable(), journal, file);
    }

    private PersistentTxnJournal journal() throws Exception {
        return new PersistentTxnJournal(
                dir.resolve("txn-" + System.nanoTime() + ".log"),
                new TxnJournal.InMemory());
    }

    private PersistentTxnJournal journalAt(Path file) throws Exception {
        return new PersistentTxnJournal(file, new TxnJournal.InMemory());
    }

    private static void prewrite(Fixture fixture, String txnId,
                                 String key, String value) {
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes(key), value == null ? null : bytes(value), false,
                txnId, bytes(key), 1, 60_000, System.currentTimeMillis(),
                java.util.Set.of());
    }

    private static TxnStateRecord record(String txnId,
                                         TxnStateRecord.State state,
                                         long startTS, long commitTS,
                                         List<TxnStateRecord.Mutation> mutations) {
        byte[] primary = mutations.isEmpty() ? new byte[0]
                : mutations.get(0).key();
        return new TxnStateRecord(txnId, state, startTS, commitTS,
                primary, mutations);
    }

    private static TxnStateRecord.Mutation mut(String key, String value,
                                               boolean deleted) {
        return new TxnStateRecord.Mutation(bytes(key),
                value == null ? null : bytes(value), deleted);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(MvccStorageEngine engine, LockTable locks,
                           PersistentTxnJournal journal, Path path)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            journal.close();
            ((MemTable) engine.underlying()).close();
        }
    }
}
