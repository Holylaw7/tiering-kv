package io.tieringkv.mvcc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 锁表（ADR-0074）：acquire/release/check/resolve。 */
public final class LockTable {

    private final Map<ByteKey, LockRecord> locks = new ConcurrentHashMap<>();

    public boolean acquire(byte[] key, LockRecord record) {
        LockRecord existing = locks.putIfAbsent(new ByteKey(key), record);
        if (existing == null || existing.txnId().equals(record.txnId())) {
            return true;
        }
        return false;
    }

    public boolean release(byte[] key, String txnId) {
        return locks.remove(new ByteKey(key), new LockRecord(key, txnId,
                new byte[0], 0, 0, LockType.WRITE));
    }

    public LockRecord check(byte[] key) {
        return locks.get(new ByteKey(key));
    }

    /** 强制移除（recovery/超时清理）。 */
    public LockRecord resolve(byte[] key) {
        return locks.remove(new ByteKey(key));
    }

    public int size() {
        return locks.size();
    }

    public Map<ByteKey, LockRecord> snapshot() {
        return Map.copyOf(locks);
    }
}
