package io.tieringkv.mvcc;

import java.util.Set;

/** 冲突检测（ADR-0074）：写写/读写/锁冲突。 */
public final class ConflictDetector {

    public void checkWriteConflict(MvccStorageEngine engine,
                                   byte[] key, long startTS) {
        for (MvccEntry entry : engine.versions(key)) {
            if (entry.commitTS() > startTS && entry.isVisible()) {
                throw new WriteConflictException(
                        "write conflict on key at " + entry.commitTS());
            }
        }
    }

    public void checkLockConflict(LockTable locks, byte[] key, String txnId) {
        LockRecord lock = locks.check(key);
        if (lock != null && !lock.txnId().equals(txnId)) {
            throw new LockConflictException(
                    "lock held by " + lock.txnId());
        }
    }

    public void checkReadWriteConflict(MvccStorageEngine engine,
                                       byte[] key, long startTS,
                                       Set<ByteKey> readSet) {
        if (!readSet.contains(new ByteKey(key))) {
            return;
        }
        checkWriteConflict(engine, key, startTS);
    }
}
