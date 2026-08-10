package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 20 事务混沌（ADR-0081/0082）：本地进程内等价验证——崩溃点注入、
 * 分区、重启、恢复重放；不变量：无幻影提交 / 无丢失提交 / 无永久锁。
 */
class Phase20TransactionChaosTest {

    @TempDir
    Path dir;

    @Test
    void killDuringPrewriteNoPhantomCommit() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v"); // 部分 prewrite 后 kill
        TxnRecoveryReplay.RecoveryResult result = replay(chaos);
        assertThat(result.committed()).isZero();
        assertThat(chaos.engine.latestValue(bytes("k"))).isNull();
        chaos.close();
    }

    @Test
    void killDuringCommitRecoveredByReplay() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        TxnRecoveryReplay.RecoveryResult result = replay(chaos);
        assertThat(result.committed()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void killAfterCommitNoLoss() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        chaos.coordinator.commit(txn.transaction(), chaos.participants());
        chaos.closeJournalAndReopen();
        TxnRecoveryReplay.RecoveryResult result = replay(chaos);
        assertThat(result.committed()).isZero();
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        chaos.close();
    }

    @Test
    void partitionBeforeCommitRollsBackAll() throws Exception {
        Chaos chaos = chaos();
        MvccStorageEngine failing = new MvccStorageEngine(
                new FailingStorage(0));
        Txn txn = chaos.begin("a", "1");
        txn.transaction().put(bytes("b"), bytes("2"));
        try {
            chaos.coordinator.commit(txn.transaction(), List.of(
                    new TransactionCoordinator.Participant("r1",
                            chaos.engine, chaos.locks),
                    new TransactionCoordinator.Participant("r2",
                            failing, new LockTable())));
        } catch (RuntimeException expected) {
            // 第二个 participant 故障 → 全回滚
        }
        assertThat(chaos.engine.latestValue(bytes("a"))).isNull();
        assertThat(chaos.engine.latestValue(bytes("b"))).isNull();
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void partitionDuringCommitDecisionDurable() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        // apply 前“分区/崩溃”：锁仍在，重放必须补完
        assertThat(chaos.locks.size()).isEqualTo(1);
        assertThat(replay(chaos).committed()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        chaos.close();
    }

    @Test
    void restartWithJournalRecovers() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        chaos.closeJournalAndReopen();
        assertThat(replay(chaos).committed()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        chaos.close();
    }

    @Test
    void concurrentTxnsUnderChaosNoPermanentLock() throws Exception {
        Chaos chaos = chaos();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        Txn txn = chaos.begin("k" + (i % 20), "w" + writer);
                        try {
                            chaos.coordinator.commit(
                                    txn.transaction(), chaos.participants());
                        } catch (RuntimeException conflict) {
                            txn.transaction().rollback(
                                    chaos.engine, chaos.locks);
                        }
                    }
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertThat(failed).isFalse();
        new TxnRecoveryReplay(chaos.engine, chaos.locks).replay(chaos.journal);
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void randomCrashRecoveryNoPhantomNoLoss() throws Exception {
        for (int round = 0; round < 15; round++) {
            Chaos chaos = chaos();
            Txn txn = chaos.begin("k", "v" + round);
            journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
            if (round % 3 == 0) {
                // 崩溃于 prewrite 后：不得提交
                prewrite(chaos, txn, "k", "v" + round);
                assertThat(replay(chaos).committed()).isZero();
            } else if (round % 3 == 1) {
                // 崩溃于 COMMIT 落盘后：必须补完
                prewrite(chaos, txn, "k", "v" + round);
                long commitTS = chaos.oracle.nextTimestamp();
                journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
                assertThat(replay(chaos).committed()).isEqualTo(1);
                assertThat(chaos.engine.latestValue(bytes("k")))
                        .isEqualTo(bytes("v" + round));
            } else {
                // 正常提交
                chaos.coordinator.commit(
                        txn.transaction(), chaos.participants());
                assertThat(chaos.engine.latestValue(bytes("k")))
                        .isEqualTo(bytes("v" + round));
            }
            chaos.close();
        }
    }

    @Test
    void leaderFailoverDuringJournalRetries() throws Exception {
        Path file = dir.resolve("retry.log");
        PersistentTxnJournal journal = new PersistentTxnJournal(file,
                new FlakyJournal(1));
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TimestampOracle oracle = new TimestampOracle();
        TransactionCoordinator coordinator =
                new TransactionCoordinator(oracle, 60_000, journal);
        Transaction transaction = new Transaction("t1",
                oracle.nextTimestamp());
        transaction.put(bytes("k"), bytes("v"));
        Txn txn = new Txn(transaction.txnId(), transaction.startTS(), "k",
                transaction, List.of(new TxnStateRecord.Mutation(
                        bytes("k"), bytes("v"), false)));
        coordinator.commit(txn.transaction(), List.of(
                new TransactionCoordinator.Participant("r1", engine, locks)));
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        journal.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void delayedPrewriteRecoveryByTimeout() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        // 仅 PREWRITE：重放不提交；锁超时由 TransactionRecoveryManager 清理
        assertThat(replay(chaos).committed()).isZero();
        new TransactionRecoveryManager(chaos.engine, 0)
                .recover(chaos.locks, System.currentTimeMillis() + 120_000);
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void multiRegionPartialPrewriteRollsBackAll() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("a", "1");
        txn.transaction().put(bytes("b"), bytes("2"));
        LockTable locksB = new LockTable();
        MvccStorageEngine engineB = new MvccStorageEngine(MemTable.create());
        // 预占 b 锁 → prewrite b 失败 → a 也必须回滚
        new PrewriteExecutor().prewrite(engineB, locksB, bytes("b"),
                bytes("other"), false, "other-txn", bytes("b"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        try {
            chaos.coordinator.commit(txn.transaction(), List.of(
                    new TransactionCoordinator.Participant("r1",
                            chaos.engine, chaos.locks),
                    new TransactionCoordinator.Participant("r2",
                            engineB, locksB)));
        } catch (RuntimeException expected) {
            // b 锁冲突
        }
        assertThat(chaos.engine.latestValue(bytes("a"))).isNull();
        assertThat(chaos.locks.size()).isZero();
        ((MemTable) engineB.underlying()).close();
        chaos.close();
    }

    @Test
    void chaosWritersAndGcConcurrent() throws Exception {
        Chaos chaos = chaos();
        io.tieringkv.mvcc.gc.BatchGcExecutor gc =
                new io.tieringkv.mvcc.gc.BatchGcExecutor(chaos.engine,
                        io.tieringkv.mvcc.gc.GcConfig.DEFAULT);
        gc.updateSafePoint(new io.tieringkv.mvcc.SafePoint(Long.MAX_VALUE / 2));
        AtomicBoolean failed = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 2_000; i++) {
                    Txn txn = chaos.begin("k" + (i % 50), "v" + i);
                    try {
                        chaos.coordinator.commit(
                                txn.transaction(), chaos.participants());
                    } catch (RuntimeException conflict) {
                        txn.transaction().rollback(
                                chaos.engine, chaos.locks);
                    }
                }
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        writer.start();
        for (int round = 0; round < 15; round++) {
            gc.gc();
        }
        writer.join(30_000);
        gc.close();
        assertThat(failed).isFalse();
        new TxnRecoveryReplay(chaos.engine, chaos.locks).replay(chaos.journal);
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void journalCorruptionTailRecoverySafe() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        chaos.closeJournalOnly();
        // 追加半条损坏记录（模拟崩溃写一半）
        java.nio.file.Files.write(chaos.journal.path(), new byte[]{0, 0, 0, 9, 1},
                java.nio.file.StandardOpenOption.APPEND);
        try (PersistentTxnJournal reopened = new PersistentTxnJournal(
                chaos.journal.path(), new TxnJournal.InMemory())) {
            assertThat(reopened.replay()).hasSize(2);
        }
        chaos.close();
    }

    @Test
    void repeatedRecoveryIdempotent() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        TxnRecoveryReplay replay = new TxnRecoveryReplay(
                chaos.engine, chaos.locks);
        assertThat(replay.replay(chaos.journal).committed()).isEqualTo(1);
        assertThat(replay.replay(chaos.journal).committed()).isZero();
        chaos.close();
    }

    @Test
    void crashAfterRollbackJournalNoPhantom() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        journal(chaos, txn, TxnStateRecord.State.ROLLBACK, 0);
        assertThat(replay(chaos).rolledBack()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isNull();
        chaos.close();
    }

    @Test
    void crashBetweenPrewriteAndCommitLocksCleanedByTimeout() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        assertThat(replay(chaos).committed()).isZero();
        new TransactionRecoveryManager(chaos.engine, -1)
                .recover(chaos.locks, System.currentTimeMillis() + 120_000);
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void chaosDeleteMutationNoLostDelete() throws Exception {
        Chaos chaos = chaos();
        chaos.engine.putVersion(bytes("k"), bytes("old"), 0, 1,
                WriteType.PUT);
        Txn txn = chaos.begin("k", null);
        txn.transaction().delete(bytes("k"));
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewriteDelete(chaos, txn, "k");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        assertThat(replay(chaos).committed()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isNull();
        chaos.close();
    }

    @Test
    void chaosMsetAllOrNothingAfterCrash() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("a", "1");
        txn.transaction().put(bytes("b"), bytes("2"));
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "a", "1");
        // b 未 prewrite 即崩溃；COMMIT 未落盘 → 不得提交
        assertThat(replay(chaos).committed()).isZero();
        assertThat(chaos.engine.latestValue(bytes("a"))).isNull();
        chaos.close();
    }

    @Test
    void concurrentRecoveryAndNewTxns() throws Exception {
        Chaos chaos = chaos();
        Txn pending = chaos.begin("p", "v");
        journal(chaos, pending, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, pending, "p", "v");
        AtomicBoolean failed = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    Txn txn = chaos.begin("n" + i, "v");
                    chaos.coordinator.commit(
                            txn.transaction(), chaos.participants());
                }
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        writer.start();
        new TxnRecoveryReplay(chaos.engine, chaos.locks).replay(chaos.journal);
        writer.join(30_000);
        assertThat(failed).isFalse();
        assertThat(chaos.locks.size()).isEqualTo(1); // pending 锁留给超时
        chaos.close();
    }

    @Test
    void chaosSnapshotRestoreThenRecovery() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        Path snapshot = dir.resolve("snap-" + System.nanoTime() + ".bin");
        io.tieringkv.mvcc.index.PersistentMvccIndex.save(snapshot,
                io.tieringkv.mvcc.index.PersistentMvccIndex.snapshot(
                        chaos.engine));
        MemTable storage = MemTable.create();
        MvccStorageEngine restored =
                io.tieringkv.mvcc.index.PersistentMvccIndex.restore(
                        snapshot, storage);
        LockTable restoredLocks = new LockTable();
        // 恢复后的锁表为空（快照不含锁）：COMMIT 重放无锁可补 → 跳过
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(restored, restoredLocks)
                        .replay(chaos.journal);
        assertThat(result.committed()).isZero();
        // 快照只含 provisional LOCK（提交尚未 apply），恢复后不可见；
        // 且锁表不在索引快照内 → 重放无法补完 → 无幻影提交
        assertThat(restored.latestValue(bytes("k"))).isNull();
        storage.close();
        chaos.close();
    }

    @Test
    void randomOperationsNoPermanentLock() throws Exception {
        Chaos chaos = chaos();
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> workers = new ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 300; i++) {
                        boolean rollback = (writer + i) % 5 == 0;
                        Txn txn = chaos.begin("r" + (i % 10),
                                "w" + writer + "-" + i);
                        try {
                            if (rollback) {
                                txn.transaction().rollback(
                                        chaos.engine, chaos.locks);
                            } else {
                                chaos.coordinator.commit(
                                        txn.transaction(), chaos.participants());
                            }
                        } catch (RuntimeException conflict) {
                            txn.transaction().rollback(
                                    chaos.engine, chaos.locks);
                        }
                    }
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        new TxnRecoveryReplay(chaos.engine, chaos.locks).replay(chaos.journal);
        assertThat(failed).isFalse();
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void journalReplayAfterLeaderRestartNoLostCommit() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        chaos.closeJournalAndReopen();
        assertThat(replay(chaos).committed()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        chaos.close();
    }

    @Test
    void chaosCrossRegionNoCrossContamination() throws Exception {
        Chaos chaos = chaos();
        MvccStorageEngine engineB = new MvccStorageEngine(MemTable.create());
        LockTable locksB = new LockTable();
        Txn txn = chaos.begin("a", "1");
        txn.transaction().put(bytes("b"), bytes("2"));
        chaos.coordinator.commit(txn.transaction(), List.of(
                new TransactionCoordinator.Participant("r1",
                        chaos.engine, chaos.locks,
                        key -> key.key().length > 0 && key.key()[0] == 'a'),
                new TransactionCoordinator.Participant("r2",
                        engineB, locksB,
                        key -> key.key().length > 0 && key.key()[0] == 'b')));
        assertThat(chaos.engine.latestValue(bytes("a"))).isEqualTo(bytes("1"));
        assertThat(engineB.latestValue(bytes("b"))).isEqualTo(bytes("2"));
        // a 只在 r1，b 只在 r2：无交叉污染
        assertThat(chaos.engine.latestValue(bytes("b"))).isNull();
        assertThat(engineB.latestValue(bytes("a"))).isNull();
        ((MemTable) engineB.underlying()).close();
        chaos.close();
    }

    @Test
    void crashDuringSecondParticipantPrewrite() throws Exception {
        Chaos chaos = chaos();
        LockTable locksB = new LockTable();
        MvccStorageEngine failing = new MvccStorageEngine(
                new FailingStorage(0));
        Txn txn = chaos.begin("a", "1");
        txn.transaction().put(bytes("b"), bytes("2"));
        try {
            chaos.coordinator.commit(txn.transaction(), List.of(
                    new TransactionCoordinator.Participant("r1",
                            chaos.engine, chaos.locks),
                    new TransactionCoordinator.Participant("r2",
                            failing, locksB)));
        } catch (RuntimeException expected) {
            // r2 存储故障 → 全回滚
        }
        assertThat(chaos.engine.latestValue(bytes("a"))).isNull();
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void recoverySkipsUnjournaledTxn() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        prewrite(chaos, txn, "k", "v"); // 未 journal 即崩溃
        TxnRecoveryReplay.RecoveryResult result = replay(chaos);
        assertThat(result.committed()).isZero();
        assertThat(result.rolledBack()).isZero();
        chaos.close();
    }

    @Test
    void chaosGcDuringRecovery() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        io.tieringkv.mvcc.gc.BatchGcExecutor gc =
                new io.tieringkv.mvcc.gc.BatchGcExecutor(chaos.engine,
                        io.tieringkv.mvcc.gc.GcConfig.DEFAULT);
        gc.updateSafePoint(new io.tieringkv.mvcc.SafePoint(Long.MAX_VALUE / 2));
        TxnRecoveryReplay replay =
                new TxnRecoveryReplay(chaos.engine, chaos.locks);
        assertThat(replay.replay(chaos.journal).committed()).isEqualTo(1);
        gc.gc();
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        gc.close();
        chaos.close();
    }

    @Test
    void chaosMetricsRecoveryCounted() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        long commitTS = chaos.oracle.nextTimestamp();
        journal(chaos, txn, TxnStateRecord.State.COMMIT, commitTS);
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        new TxnRecoveryReplay(chaos.engine, chaos.locks, metrics)
                .replay(chaos.journal);
        assertThat(metrics.snapshot().recoveryTxn()).isEqualTo(1);
        chaos.close();
    }

    @Test
    void chaosRollbackIdempotentAcrossRestarts() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        prewrite(chaos, txn, "k", "v");
        journal(chaos, txn, TxnStateRecord.State.ROLLBACK, 0);
        TxnRecoveryReplay replay =
                new TxnRecoveryReplay(chaos.engine, chaos.locks);
        assertThat(replay.replay(chaos.journal).rolledBack()).isEqualTo(1);
        chaos.closeJournalAndReopen();
        assertThat(replay.replay(chaos.journal).rolledBack()).isZero();
        chaos.close();
    }

    @Test
    void chaosCommitDecisionBeforeParticipantFailureCompletes()
            throws Exception {
        Chaos chaos = chaos();
        MvccStorageEngine failing = new MvccStorageEngine(
                new FailingStorage(2));
        Txn txn = chaos.begin("k", "v");
        try {
            chaos.coordinator.commit(txn.transaction(), List.of(
                    new TransactionCoordinator.Participant("r1",
                            failing, chaos.locks)));
        } catch (RuntimeException expected) {
            // prewrite（第 1 次 put）成功，commit（第 2 次 put）失败：
            // COMMIT 已持久化 → 保持 PREPARED，重放补完
        }
        assertThat(txn.transaction().state())
                .isEqualTo(Transaction.State.PREPARED);
        assertThat(replay(chaos).committed()).isEqualTo(1);
        assertThat(chaos.engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        assertThat(chaos.locks.size()).isZero();
        chaos.close();
    }

    @Test
    void chaosNoPhantomAfterAbortedPrewriteRetry() throws Exception {
        Chaos chaos = chaos();
        Txn txn = chaos.begin("k", "v");
        journal(chaos, txn, TxnStateRecord.State.PREWRITE, 0);
        // prewrite 抛错（锁冲突）→ 协调器回滚并记录 ROLLBACK
        new PrewriteExecutor().prewrite(chaos.engine, chaos.locks,
                bytes("k"), bytes("other"), false, "other-txn",
                bytes("k"), 1, 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        try {
            chaos.coordinator.commit(
                    txn.transaction(), chaos.participants());
        } catch (RuntimeException expected) {
            // 冲突
        }
        TxnRecoveryReplay.RecoveryResult result = replay(chaos);
        assertThat(result.committed()).isZero();
        assertThat(chaos.engine.latestValue(bytes("k"))).isNull();
        chaos.close();
    }

    // ---------- harness ----------

    private static void journal(Chaos chaos, Txn txn,
                                TxnStateRecord.State state, long commitTS) {
        chaos.journal.recordState(new TxnStateRecord(txn.txnId(), state,
                txn.startTS(), commitTS, bytes(txn.primary()),
                txn.mutations())).exceptionally(error -> null).join();
    }

    private static void prewrite(Chaos chaos, Txn txn, String key,
                                 String value) {
        new PrewriteExecutor().prewrite(chaos.engine, chaos.locks,
                bytes(key), value == null ? null : bytes(value), false,
                txn.txnId(), bytes(key), txn.startTS(), 60_000,
                System.currentTimeMillis(), java.util.Set.of());
    }

    private static void prewriteDelete(Chaos chaos, Txn txn, String key) {
        new PrewriteExecutor().prewrite(chaos.engine, chaos.locks,
                bytes(key), null, true, txn.txnId(), bytes(key),
                txn.startTS(), 60_000, System.currentTimeMillis(),
                java.util.Set.of());
    }

    private static TxnRecoveryReplay.RecoveryResult replay(Chaos chaos)
            throws Exception {
        return new TxnRecoveryReplay(chaos.engine, chaos.locks)
                .replay(chaos.journal);
    }

    private Chaos chaos() throws Exception {
        Path file = dir.resolve("chaos-" + System.nanoTime() + ".log");
        PersistentTxnJournal journal = new PersistentTxnJournal(
                file, new TxnJournal.InMemory());
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TimestampOracle oracle = new TimestampOracle();
        TransactionCoordinator coordinator =
                new TransactionCoordinator(oracle, 60_000, journal);
        return new Chaos(engine, locks, journal, oracle, coordinator);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Txn(String txnId, long startTS, String primary,
                       Transaction transaction,
                       List<TxnStateRecord.Mutation> mutations) {
    }

    private static final class Chaos implements AutoCloseable {
        private final MvccStorageEngine engine;
        private final LockTable locks;
        private final PersistentTxnJournal journal;
        private final TimestampOracle oracle;
        private final TransactionCoordinator coordinator;

        private Chaos(MvccStorageEngine engine, LockTable locks,
                      PersistentTxnJournal journal, TimestampOracle oracle,
                      TransactionCoordinator coordinator) {
            this.engine = engine;
            this.locks = locks;
            this.journal = journal;
            this.oracle = oracle;
            this.coordinator = coordinator;
        }

        private Txn begin(String key, String value) {
            long startTS = oracle.nextTimestamp();
            Transaction transaction = new Transaction(
                    "chaos-" + startTS, startTS);
            if (value == null) {
                transaction.delete(bytes(key));
            } else {
                transaction.put(bytes(key), bytes(value));
            }
            List<TxnStateRecord.Mutation> mutations = new ArrayList<>();
            for (ByteKey write : transaction.writeKeys()) {
                mutations.add(new TxnStateRecord.Mutation(write.key(),
                        transaction.writeValue(write), false));
            }
            for (ByteKey del : transaction.deleteKeys()) {
                mutations.add(new TxnStateRecord.Mutation(
                        del.key(), null, true));
            }
            return new Txn(transaction.txnId(), startTS, key, transaction,
                    mutations);
        }

        private List<TransactionCoordinator.Participant> participants() {
            return List.of(new TransactionCoordinator.Participant(
                    "r1", engine, locks));
        }

        private void closeJournalOnly() throws Exception {
            journal.close();
        }

        private void closeJournalAndReopen() throws Exception {
            journal.close();
        }

        @Override
        public void close() throws Exception {
            journal.close();
            ((MemTable) engine.underlying()).close();
        }
    }

    /** 前 N 次提案失败（leader 变更），之后成功。 */
    private static final class FlakyJournal implements TxnJournal {
        private final int failCount;
        private int attempts;

        private FlakyJournal(int failCount) {
            this.failCount = failCount;
        }

        @Override
        public CompletableFuture<Void> record(byte[] command) {
            if (attempts++ < failCount) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("not leader"));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * 故障存储：每个 key 第 failOnPutNumber 次 put 抛错（模拟 apply 阶段
     * 故障）；0=首次即失败，2=prewrite 成功、commit 失败。
     */
    private static final class FailingStorage
            implements io.tieringkv.storage.StorageEngine {
        private final int failOnPutNumber;
        private final AtomicInteger puts = new AtomicInteger();

        private FailingStorage(int failOnPutNumber) {
            this.failOnPutNumber = failOnPutNumber;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            put(key, value, NO_TTL);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            int count = puts.incrementAndGet();
            // failOnPutNumber=0 → 第 1 次 put 失败；=N → 第 N 次 put 失败
            int failOn = failOnPutNumber == 0 ? 1 : failOnPutNumber;
            if (count == failOn) {
                throw new IllegalStateException("participant down");
            }
        }

        @Override
        public byte[] get(byte[] key) {
            return null;
        }

        @Override
        public boolean delete(byte[] key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            return new io.tieringkv.storage.StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public io.tieringkv.storage.memory.KeyValueEntry next() {
                    throw new IllegalStateException("empty");
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long size() {
            return 0;
        }
    }
}
