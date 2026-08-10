package io.tieringkv.mvcc;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务恢复重放（ADR-0081）：COMMIT 已持久化 → 补完提交（无丢失）；
 * ROLLBACK 已持久化 → 清理锁与 provisional；仅 PREWRITE → 留给超时恢复
 * （无幻影提交）。重放幂等。
 */
public final class TxnRecoveryReplay {

    private final MvccStorageEngine engine;
    private final LockTable locks;
    private final TransactionMetricsRegistry metrics;

    public TxnRecoveryReplay(MvccStorageEngine engine, LockTable locks) {
        this(engine, locks, null);
    }

    public TxnRecoveryReplay(MvccStorageEngine engine, LockTable locks,
                             TransactionMetricsRegistry metrics) {
        this.engine = engine;
        this.locks = locks;
        this.metrics = metrics;
    }

    public RecoveryResult replay(PersistentTxnJournal journal)
            throws IOException {
        return replay(journal.replay());
    }

    public RecoveryResult replay(List<TxnStateRecord> records) {
        long committed = 0;
        long rolledBack = 0;
        long skipped = 0;
        Map<String, TxnStateRecord> prewrites = new HashMap<>();
        for (TxnStateRecord record : records) {
            if (record.state() == TxnStateRecord.State.PREWRITE) {
                prewrites.put(record.txnId(), record);
            }
        }
        for (TxnStateRecord record : records) {
            switch (record.state()) {
                case COMMIT -> {
                    TxnStateRecord prewrite = prewrites.get(record.txnId());
                    if (prewrite == null) {
                        skipped++;
                    } else if (completeCommit(prewrite, record.commitTS())) {
                        committed++;
                    } else {
                        skipped++;
                    }
                }
                case ROLLBACK -> {
                    if (rollbackTxn(record.txnId())) {
                        rolledBack++;
                    } else {
                        skipped++;
                    }
                }
                default -> skipped++;
            }
        }
        if (metrics != null) {
            for (long i = 0; i < committed; i++) {
                metrics.recordRecovery();
            }
            for (long i = 0; i < rolledBack; i++) {
                metrics.recordRecovery();
            }
        }
        return new RecoveryResult(committed, rolledBack, skipped);
    }

    /** 补完提交：对仍持有本事务锁的 key 写已提交版本并释放锁。 */
    private boolean completeCommit(TxnStateRecord prewrite, long commitTS) {
        boolean any = false;
        for (TxnStateRecord.Mutation mutation : prewrite.mutations()) {
            LockRecord lock = locks.check(mutation.key());
            if (lock == null || !lock.txnId().equals(prewrite.txnId())) {
                continue; // 已提交或未 prewrite：幂等跳过
            }
            engine.putVersion(mutation.key(),
                    mutation.deleted() ? null : mutation.value(),
                    prewrite.startTS(), commitTS,
                    mutation.deleted() ? WriteType.DELETE : WriteType.PUT);
            engine.deleteVersion(mutation.key(), prewrite.startTS());
            locks.release(mutation.key(), prewrite.txnId());
            any = true;
        }
        return any;
    }

    private boolean rollbackTxn(String txnId) {
        boolean any = false;
        RollbackExecutor rollback = new RollbackExecutor();
        for (Map.Entry<ByteKey, LockRecord> entry : locks.snapshot().entrySet()) {
            LockRecord lock = entry.getValue();
            if (lock.txnId().equals(txnId)) {
                rollback.rollback(engine, locks, lock.key(),
                        txnId, lock.startTS());
                any = true;
            }
        }
        return any;
    }

    public record RecoveryResult(long committed, long rolledBack, long skipped) {
    }
}
