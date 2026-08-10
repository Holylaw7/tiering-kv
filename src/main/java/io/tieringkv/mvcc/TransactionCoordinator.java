package io.tieringkv.mvcc;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** 跨 Region 2PC 协调器（ADR-0073）：全 participant prewrite 成功才 commit。 */
public final class TransactionCoordinator {

    public record Participant(String regionId,
                              MvccStorageEngine engine,
                              LockTable locks,
                              Predicate<ByteKey> ownsKey) {

        public Participant(String regionId, MvccStorageEngine engine,
                           LockTable locks) {
            this(regionId, engine, locks, ignored -> true);
        }

        public boolean owns(ByteKey key) {
            return ownsKey.test(key);
        }
    }

    private final TimestampOracle oracle;
    private final long lockTtlMillis;
    private final PersistentTxnJournal journal;

    public TransactionCoordinator(TimestampOracle oracle, long lockTtlMillis) {
        this(oracle, lockTtlMillis, null);
    }

    public TransactionCoordinator(TimestampOracle oracle, long lockTtlMillis,
                                  PersistentTxnJournal journal) {
        this.oracle = oracle;
        this.lockTtlMillis = lockTtlMillis;
        this.journal = journal;
    }

    /** 2PC：prewrite 全部 participant → commit 全部；失败 rollback all。 */
    public Transaction commit(Transaction txn, List<Participant> participants) {
        List<Participant> prepared = new ArrayList<>();
        boolean commitJournaled = false;
        try {
            journalState(TxnStateRecord.State.PREWRITE, txn, 0);
            for (Participant participant : participants) {
                prewriteOn(participant, txn);
                prepared.add(participant);
            }
            long commitTS = oracle.nextTimestamp();
            txn.markPrepared(commitTS);
            // COMMIT 决定先持久化：此后即使参与者失败也不回滚，交给恢复补完
            commitJournaled = journalAppend(
                    TxnStateRecord.State.COMMIT, txn, commitTS);
            journalPropose(TxnStateRecord.State.COMMIT, txn, commitTS);
            for (Participant participant : prepared) {
                commitOn(participant, txn, commitTS);
            }
            txn.markCommitted(commitTS);
            return txn;
        } catch (RuntimeException e) {
            if (journal == null || !commitJournaled) {
                try {
                    journalState(TxnStateRecord.State.ROLLBACK, txn, 0);
                } catch (RuntimeException journalFailure) {
                    // 回滚日志失败不掩盖原始异常；恢复阶段仍可依据锁超时清理
                }
                for (Participant participant : prepared) {
                    rollbackOn(participant, txn);
                }
                txn.markRolledBack();
            } else {
                // COMMIT 已持久化：不允许回滚（避免与恢复补完冲突），
                // 事务保持 PREPARED，由 TxnRecoveryReplay 幂等补完。
            }
            throw e;
        }
    }

    private void journalState(TxnStateRecord.State state, Transaction txn,
                              long commitTS) {
        if (journal == null) {
            return;
        }
        TxnStateRecord record = record(state, txn, commitTS);
        journal.appendLocal(record);
        journal.propose(record).join();
    }

    private boolean journalAppend(TxnStateRecord.State state, Transaction txn,
                                  long commitTS) {
        if (journal == null) {
            return true;
        }
        return journal.appendLocal(record(state, txn, commitTS));
    }

    private void journalPropose(TxnStateRecord.State state, Transaction txn,
                                long commitTS) {
        if (journal == null) {
            return;
        }
        journal.propose(record(state, txn, commitTS)).join();
    }

    private TxnStateRecord record(TxnStateRecord.State state, Transaction txn,
                                  long commitTS) {
        List<TxnStateRecord.Mutation> mutations = new ArrayList<>();
        for (ByteKey key : txn.writeKeys()) {
            mutations.add(new TxnStateRecord.Mutation(
                    key.key(), txn.writeValue(key), false));
        }
        for (ByteKey key : txn.deleteKeys()) {
            mutations.add(new TxnStateRecord.Mutation(key.key(), null, true));
        }
        byte[] primary = txn.primaryKeyOr(mutations.isEmpty()
                ? new byte[0] : mutations.get(0).key());
        return new TxnStateRecord(txn.txnId(), state,
                txn.startTS(), commitTS, primary, mutations);
    }

    private void prewriteOn(Participant participant, Transaction txn) {
        PrewriteExecutor prewrite = new PrewriteExecutor();
        try {
            for (ByteKey key : txn.writeKeys()) {
                if (!participant.owns(key)) {
                    continue;
                }
                prewrite.prewrite(participant.engine(), participant.locks(),
                        key.key(), txn.writeValue(key), false,
                        txn.txnId(), txn.primaryKeyOr(key.key()),
                        txn.startTS(), lockTtlMillis,
                        System.currentTimeMillis(), txn.readSet());
            }
            for (ByteKey key : txn.deleteKeys()) {
                if (!participant.owns(key)) {
                    continue;
                }
                prewrite.prewrite(participant.engine(), participant.locks(),
                        key.key(), null, true,
                        txn.txnId(), txn.primaryKeyOr(key.key()),
                        txn.startTS(), lockTtlMillis,
                        System.currentTimeMillis(), txn.readSet());
            }
        } catch (RuntimeException e) {
            // 本 participant 部分 prewrite 已写入：先回滚自身再上抛
            rollbackOn(participant, txn);
            throw e;
        }
    }

    private void commitOn(Participant participant, Transaction txn,
                          long commitTS) {
        CommitExecutor commit = new CommitExecutor();
        for (ByteKey key : txn.writeKeys()) {
            if (!participant.owns(key)) {
                continue;
            }
            commit.commit(participant.engine(), participant.locks(),
                    key.key(), txn.writeValue(key), false,
                    txn.txnId(), txn.startTS(), commitTS);
        }
        for (ByteKey key : txn.deleteKeys()) {
            if (!participant.owns(key)) {
                continue;
            }
            commit.commit(participant.engine(), participant.locks(),
                    key.key(), null, true,
                    txn.txnId(), txn.startTS(), commitTS);
        }
    }

    private void rollbackOn(Participant participant, Transaction txn) {
        RollbackExecutor rollback = new RollbackExecutor();
        for (ByteKey key : txn.writeKeys()) {
            if (!participant.owns(key)) {
                continue;
            }
            rollback.rollback(participant.engine(), participant.locks(),
                    key.key(), txn.txnId(), txn.startTS());
        }
        for (ByteKey key : txn.deleteKeys()) {
            if (!participant.owns(key)) {
                continue;
            }
            rollback.rollback(participant.engine(), participant.locks(),
                    key.key(), txn.txnId(), txn.startTS());
        }
    }
}
