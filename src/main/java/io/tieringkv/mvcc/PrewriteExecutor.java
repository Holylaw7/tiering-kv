package io.tieringkv.mvcc;

/** Prewrite（ADR-0073）：冲突检查 + 写锁 + provisional（LOCK 版本，不可见）。 */
public final class PrewriteExecutor {

    private final ConflictDetector detector = new ConflictDetector();

    public void prewrite(MvccStorageEngine engine, LockTable locks,
                         byte[] key, byte[] value, boolean deleted,
                         String txnId, byte[] primary,
                         long startTS, long lockTtlMillis, long now,
                         java.util.Set<ByteKey> readSet) {
        detector.checkLockConflict(locks, key, txnId);
        detector.checkReadWriteConflict(engine, key, startTS, readSet);
        detector.checkWriteConflict(engine, key, startTS);
        LockRecord lock = new LockRecord(key, txnId, primary,
                startTS, lockTtlMillis, LockType.WRITE, now);
        if (!locks.acquire(key, lock)) {
            throw new LockConflictException("lock held on key");
        }
        // provisional：commitTS=startTS，writeType=LOCK（读者跳过）
        engine.putVersion(key, value, startTS, startTS, WriteType.LOCK);
    }
}
