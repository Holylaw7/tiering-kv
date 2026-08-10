package io.tieringkv.mvcc;

import java.util.Map;

/** 事务恢复（ADR-0076）：超时锁回滚；primary 已提交则补完 commit。 */
public final class TransactionRecoveryManager {

    private final MvccStorageEngine engine;
    private final long lockTtlMillis;

    public TransactionRecoveryManager(MvccStorageEngine engine,
                                      long lockTtlMillis) {
        this.engine = engine;
        this.lockTtlMillis = lockTtlMillis;
    }

    public RecoveryResult recover(LockTable locks, long now) {
        long rolledBack = 0;
        long committed = 0;
        for (Map.Entry<ByteKey, LockRecord> entry : locks.snapshot().entrySet()) {
            LockRecord lock = entry.getValue();
            boolean primaryCommitted = lock.primary() != null
                    && hasCommitAfter(lock.primary(), lock.startTS());
            if (primaryCommitted) {
                // primary 已提交：清理该事务全部锁（版本已由 commit 写入）
                for (Map.Entry<ByteKey, LockRecord> other : locks.snapshot()
                        .entrySet()) {
                    if (other.getValue().txnId().equals(lock.txnId())) {
                        locks.resolve(other.getKey().key());
                        committed++;
                    }
                }
            } else if (lock.expired(now)) {
                RollbackExecutor rollback = new RollbackExecutor();
                rollback.rollback(engine, locks, lock.key(),
                        lock.txnId(), lock.startTS());
                rolledBack++;
            }
        }
        return new RecoveryResult(rolledBack, committed);
    }

    private boolean hasCommitAfter(byte[] key, long startTS) {
        for (MvccEntry entry : engine.versions(key)) {
            if (entry.isVisible() && entry.commitTS() > startTS) {
                return true;
            }
        }
        return false;
    }

    public record RecoveryResult(long rolledBack, long committed) {
    }
}
