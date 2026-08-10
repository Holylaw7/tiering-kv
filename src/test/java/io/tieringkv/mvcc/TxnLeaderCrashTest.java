package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Leader 崩溃恢复（ADR-0081）：无幻影提交、无丢失提交、可重放。 */
class TxnLeaderCrashTest {

    @TempDir
    Path dir;

    @Test
    void crashAfterPrewriteJournalNoPhantomCommit() throws Exception {
        Fixture fixture = fixture(new FlakyProposer(99));
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        // PREWRITE 已持久化、COMMIT 未持久化：Raft 持续失败 → 协调器回滚
        try {
            fixture.coordinator.commit(txn, fixture.participants());
        } catch (RuntimeException ignored) {
            // raft 提案失败
        }
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void crashAfterCommitJournalRecoversCommit() throws Exception {
        Fixture fixture = fixture(new FlakyProposer(99));
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        // 模拟：PREWRITE + COMMIT 已落盘，apply 前崩溃 → 重放补完提交
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.PREWRITE, txn.startTS(), 0,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("k"), bytes("v"), false, txn.txnId(), bytes("k"),
                txn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        long commitTS = fixture.oracle.nextTimestamp();
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.COMMIT, txn.startTS(), commitTS,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    @Test
    void crashAfterApplyReplayIsNoop() throws Exception {
        Fixture fixture = fixture(new TxnJournal.InMemory());
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.coordinator.commit(txn, fixture.participants());
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void rollbackCrashNoPhantomCommit() throws Exception {
        Fixture fixture = fixture(new FlakyProposer(99));
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.PREWRITE, txn.startTS(), 0,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("k"), bytes("v"), false, txn.txnId(), bytes("k"),
                txn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.ROLLBACK, txn.startTS(), 0,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal);
        assertThat(fixture.locks.size()).isZero();
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void raftProposeFailureBeforeCommitNoPhantom() throws Exception {
        // PREWRITE 的 Raft 提案失败 → 协调器回滚；本地 PREWRITE 记录无害
        Fixture fixture = fixture(new FailingProposer());
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.coordinator.commit(txn, fixture.participants());
        } catch (RuntimeException expected) {
            // raft 提案失败
        }
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isZero();
        assertThat(result.rolledBack()).isZero();
        fixture.close();
    }

    @Test
    void raftProposeFailureAfterCommitStillRecovers() throws Exception {
        // COMMIT 已本地持久化但 Raft 提案失败：客户端收到错误，
        // 但重放必须补完提交（at-least-once，无丢失）
        Fixture fixture = fixture(new FailingProposer());
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        TxnStateRecord prewrite = new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.PREWRITE, txn.startTS(), 0,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false)));
        recordIgnoringRaft(fixture.journal, prewrite);
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("k"), bytes("v"), false, txn.txnId(), bytes("k"),
                txn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        long commitTS = fixture.oracle.nextTimestamp();
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.COMMIT, txn.startTS(), commitTS,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void leaderChangeRetriesJournalProposal() throws Exception {
        // Raft 提案失败一次（leader 变更）后重试成功
        Fixture fixture = fixture(new FlakyProposer(1));
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.coordinator.commit(txn, fixture.participants());
        assertThat(fixture.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        fixture.close();
    }

    @Test
    void concurrentTransactionsRecoverIndependently() throws Exception {
        Fixture fixture = fixture(new FlakyProposer(99));
        Transaction commitTxn = fixture.manager.begin();
        commitTxn.put(bytes("a"), bytes("1"));
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(
                commitTxn.txnId(), TxnStateRecord.State.PREWRITE,
                commitTxn.startTS(), 0, bytes("a"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("a"), bytes("1"), false))));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("a"), bytes("1"), false, commitTxn.txnId(), bytes("a"),
                commitTxn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        long commitTS = fixture.oracle.nextTimestamp();
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(
                commitTxn.txnId(), TxnStateRecord.State.COMMIT,
                commitTxn.startTS(), commitTS, bytes("a"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("a"), bytes("1"), false))));

        Transaction rollbackTxn = fixture.manager.begin();
        rollbackTxn.put(bytes("b"), bytes("2"));
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(
                rollbackTxn.txnId(), TxnStateRecord.State.PREWRITE,
                rollbackTxn.startTS(), 0, bytes("b"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("b"), bytes("2"), false))));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("b"), bytes("2"), false, rollbackTxn.txnId(), bytes("b"),
                rollbackTxn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(
                rollbackTxn.txnId(), TxnStateRecord.State.ROLLBACK,
                rollbackTxn.startTS(), 0, bytes("b"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("b"), bytes("2"), false))));

        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("a"))).isEqualTo(bytes("1"));
        assertThat(fixture.engine.latestValue(bytes("b"))).isNull();
        fixture.close();
    }

    @Test
    void recoveryMetricsRecordedAfterCrash() throws Exception {
        Fixture fixture = fixture(new FlakyProposer(99));
        Transaction txn = fixture.manager.begin();
        txn.put(bytes("k"), bytes("v"));
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.PREWRITE, txn.startTS(), 0,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("k"), bytes("v"), false, txn.txnId(), bytes("k"),
                txn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        long commitTS = fixture.oracle.nextTimestamp();
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.COMMIT, txn.startTS(), commitTS,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), bytes("v"), false))));
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        new TxnRecoveryReplay(fixture.engine, fixture.locks, metrics)
                .replay(fixture.journal);
        assertThat(metrics.snapshot().recoveryTxn()).isEqualTo(1);
        fixture.close();
    }

    @Test
    void journalFileSurvivesLeaderRestart() throws Exception {
        Path file = dir.resolve("restart.log");
        PersistentTxnJournal first = new PersistentTxnJournal(
                file, new TxnJournal.InMemory());
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        Transaction txn = new Transaction("t1", 1);
        txn.put(bytes("k"), bytes("v"));
        first.recordState(new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.PREWRITE, 1, 0, bytes("k"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("k"), bytes("v"), false)))).join();
        new PrewriteExecutor().prewrite(engine, locks, bytes("k"), bytes("v"),
                false, "t1", bytes("k"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        first.recordState(new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.COMMIT, 1, 2, bytes("k"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("k"), bytes("v"), false)))).join();
        first.close(); // leader 重启

        try (PersistentTxnJournal reopened =
                     new PersistentTxnJournal(file, new TxnJournal.InMemory())) {
            TxnRecoveryReplay.RecoveryResult result =
                    new TxnRecoveryReplay(engine, locks)
                            .replay(reopened);
            assertThat(result.committed()).isEqualTo(1);
        }
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void deleteMutationRecoveredAfterCrash() throws Exception {
        Fixture fixture = fixture(new FlakyProposer(99));
        fixture.engine.putVersion(bytes("k"), bytes("old"), 0, 1,
                WriteType.PUT);
        Transaction txn = fixture.manager.begin();
        txn.delete(bytes("k"));
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.PREWRITE, txn.startTS(), 0,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), null, true))));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                bytes("k"), null, true, txn.txnId(), bytes("k"),
                txn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        long commitTS = fixture.oracle.nextTimestamp();
        recordIgnoringRaft(fixture.journal, new TxnStateRecord(txn.txnId(),
                TxnStateRecord.State.COMMIT, txn.startTS(), commitTS,
                bytes("k"), List.of(new TxnStateRecord.Mutation(
                bytes("k"), null, true))));
        new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal);
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    // ---------- helpers ----------

    private Fixture fixture(TxnJournal raft) throws Exception {
        Path file = dir.resolve("txn-" + System.nanoTime() + ".log");
        PersistentTxnJournal journal = new PersistentTxnJournal(file, raft);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TimestampOracle oracle = new TimestampOracle();
        TransactionManager manager = new TransactionManager(
                oracle, engine, locks, 60_000);
        TransactionCoordinator coordinator =
                new TransactionCoordinator(oracle, 60_000, journal);
        return new Fixture(engine, locks, journal, oracle, manager,
                coordinator);
    }

    /** 本地日志优先落盘，Raft 失败可忽略（恢复重放以本地为准）。 */
    private static void recordIgnoringRaft(PersistentTxnJournal journal,
                                           TxnStateRecord record) {
        journal.recordState(record).exceptionally(error -> null).join();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(MvccStorageEngine engine, LockTable locks,
                           PersistentTxnJournal journal, TimestampOracle oracle,
                           TransactionManager manager,
                           TransactionCoordinator coordinator)
            implements AutoCloseable {
        List<TransactionCoordinator.Participant> participants() {
            return List.of(new TransactionCoordinator.Participant(
                    "r1", engine, locks));
        }

        @Override
        public void close() throws Exception {
            journal.close();
            ((MemTable) engine.underlying()).close();
        }
    }

    /** 前 N 次提案失败（模拟 leader 变更），之后成功。 */
    private static final class FlakyProposer implements TxnJournal {
        private final AtomicInteger attempts = new AtomicInteger();
        private final int failCount;

        private FlakyProposer(int failCount) {
            this.failCount = failCount;
        }

        @Override
        public CompletableFuture<Void> record(byte[] command) {
            if (attempts.getAndIncrement() < failCount) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("not leader"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 所有提案失败（模拟持续故障）。 */
    private static final class FailingProposer implements TxnJournal {
        @Override
        public CompletableFuture<Void> record(byte[] command) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("raft down"));
        }
    }
}
