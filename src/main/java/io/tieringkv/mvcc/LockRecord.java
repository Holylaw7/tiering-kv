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
    // 墙上时钟创建时刻（锁超时判定专用）：startTS 是 HLC 尺度（约
    // physicalMillis*1e6），与 System.currentTimeMillis() 不可直接比较。
    private final long createdAtMillis;

    public LockRecord(byte[] key, String txnId, byte[] primary,
                      long startTS, long ttlMillis, LockType lockType) {
        this(key, txnId, primary, startTS, ttlMillis, lockType,
                startTS);
    }

    public LockRecord(byte[] key, String txnId, byte[] primary,
                      long startTS, long ttlMillis, LockType lockType,
                      long createdAtMillis) {
        this.key = key.clone();
        this.txnId = txnId;
        this.primary = primary.clone();
        this.startTS = startTS;
        this.ttlMillis = ttlMillis;
        this.lockType = lockType;
        this.createdAtMillis = createdAtMillis;
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

    public long createdAtMillis() {
        return createdAtMillis;
    }

    /** 锁是否过期：基于墙上时钟创建时刻（Phase 20 修复 HLC 尺度错配）。 */
    public boolean expired(long nowMillis) {
        return nowMillis - createdAtMillis > ttlMillis;
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
