package io.tieringkv.mvcc;

/** Commit（ADR-0073）：写 WriteRecord（commitTS）→ 删 provisional → 释放锁。 */
public final class CommitExecutor {

    public void commit(MvccStorageEngine engine, LockTable locks,
                       byte[] key, byte[] value, boolean deleted,
                       String txnId, long startTS, long commitTS) {
        LockRecord lock = locks.check(key);
        if (lock == null || !lock.txnId().equals(txnId)) {
            throw new TransactionAbortedException("primary lock missing");
        }
        engine.putVersion(key, value, startTS, commitTS,
                deleted ? WriteType.DELETE : WriteType.PUT);
        engine.deleteVersion(key, startTS); // 删 provisional
        locks.release(key, txnId);
    }
}
