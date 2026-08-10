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
        boolean[] removed = {false};
        locks.computeIfPresent(new ByteKey(key), (k, record) -> {
            if (record.txnId().equals(txnId)) {
                removed[0] = true;
                return null;
            }
            return record;
        });
        return removed[0];
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
