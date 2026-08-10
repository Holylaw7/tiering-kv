package io.tieringkv.mvcc;

import java.util.ArrayList;
import java.util.List;

/** 跨 Region 2PC 协调器（ADR-0073）：全 participant prewrite 成功才 commit。 */
public final class TransactionCoordinator {

    public record Participant(String regionId,
                              MvccStorageEngine engine,
                              LockTable locks) {
    }

    private final TimestampOracle oracle;
    private final long lockTtlMillis;

    public TransactionCoordinator(TimestampOracle oracle, long lockTtlMillis) {
        this.oracle = oracle;
        this.lockTtlMillis = lockTtlMillis;
    }

    /** 2PC：prewrite 全部 participant → commit 全部；失败 rollback all。 */
    public Transaction commit(Transaction txn, List<Participant> participants) {
        List<Participant> prepared = new ArrayList<>();
        try {
            for (Participant participant : participants) {
                prewriteOn(participant, txn);
                prepared.add(participant);
            }
            long commitTS = oracle.nextTimestamp();
            txn.markPrepared(commitTS);
            for (Participant participant : prepared) {
                commitOn(participant, txn, commitTS);
            }
            txn.markCommitted(commitTS);
            return txn;
        } catch (RuntimeException e) {
            for (Participant participant : prepared) {
                rollbackOn(participant, txn);
            }
            txn.markRolledBack();
            throw e;
        }
    }

    private void prewriteOn(Participant participant, Transaction txn) {
        PrewriteExecutor prewrite = new PrewriteExecutor();
        for (ByteKey key : txn.writeKeys()) {
            prewrite.prewrite(participant.engine(), participant.locks(),
                    key.key(), txn.writeValue(key), false,
                    txn.txnId(), txn.primaryKeyOr(key.key()),
                    txn.startTS(), lockTtlMillis,
                    System.currentTimeMillis(), txn.readSet());
        }
        for (ByteKey key : txn.deleteKeys()) {
            prewrite.prewrite(participant.engine(), participant.locks(),
                    key.key(), null, true,
                    txn.txnId(), txn.primaryKeyOr(key.key()),
                    txn.startTS(), lockTtlMillis,
                    System.currentTimeMillis(), txn.readSet());
        }
    }

    private void commitOn(Participant participant, Transaction txn,
                          long commitTS) {
        CommitExecutor commit = new CommitExecutor();
        for (ByteKey key : txn.writeKeys()) {
            commit.commit(participant.engine(), participant.locks(),
                    key.key(), txn.writeValue(key), false,
                    txn.txnId(), txn.startTS(), commitTS);
        }
        for (ByteKey key : txn.deleteKeys()) {
            commit.commit(participant.engine(), participant.locks(),
                    key.key(), null, true,
                    txn.txnId(), txn.startTS(), commitTS);
        }
    }

    private void rollbackOn(Participant participant, Transaction txn) {
        RollbackExecutor rollback = new RollbackExecutor();
        for (ByteKey key : txn.writeKeys()) {
            rollback.rollback(participant.engine(), participant.locks(),
                    key.key(), txn.txnId(), txn.startTS());
        }
        for (ByteKey key : txn.deleteKeys()) {
            rollback.rollback(participant.engine(), participant.locks(),
                    key.key(), txn.txnId(), txn.startTS());
        }
    }
}
