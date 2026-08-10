package io.tieringkv.mvcc;

/** Rollback（ADR-0073）：删 provisional + 释放锁。 */
public final class RollbackExecutor {

    public void rollback(MvccStorageEngine engine, LockTable locks,
                         byte[] key, String txnId, long startTS) {
        engine.deleteVersion(key, startTS);
        locks.release(key, txnId);
    }
}
