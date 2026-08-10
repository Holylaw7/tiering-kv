package io.tieringkv.mvcc;

import java.util.Arrays;

/** 锁记录（ADR-0074）：primary/txnId/startTS/ttl/type。 */
public final class LockRecord {

    private final byte[] key;
    private final String txnId;
    private final byte[] primary;
    private final long startTS;
    private final long ttlMillis;
    private final LockType lockType;

    public LockRecord(byte[] key, String txnId, byte[] primary,
                      long startTS, long ttlMillis, LockType lockType) {
        this.key = key.clone();
        this.txnId = txnId;
        this.primary = primary.clone();
        this.startTS = startTS;
        this.ttlMillis = ttlMillis;
        this.lockType = lockType;
    }

    public byte[] key() {
        return key.clone();
    }

    public String txnId() {
        return txnId;
    }

    public byte[] primary() {
        return primary.clone();
    }

    public long startTS() {
        return startTS;
    }

    public long ttlMillis() {
        return ttlMillis;
    }

    public LockType lockType() {
        return lockType;
    }

    public boolean expired(long now) {
        return now - startTS > ttlMillis;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LockRecord that
                && Arrays.equals(key, that.key)
                && txnId.equals(that.txnId)
                && Arrays.equals(primary, that.primary)
                && startTS == that.startTS
                && ttlMillis == that.ttlMillis
                && lockType == that.lockType;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + txnId.hashCode();
        result = 31 * result + Arrays.hashCode(primary);
        result = 31 * result + Long.hashCode(startTS);
        result = 31 * result + Long.hashCode(ttlMillis);
        result = 31 * result + lockType.hashCode();
        return result;
    }
}
