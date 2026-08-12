package io.tieringkv.transaction.pessimistic;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 悲观事务（ADR-0208）：提前加锁 + 读写可见性 + 死锁超时。 */
public final class PessimisticTransaction {

    private final Map<String, String> locks = new ConcurrentHashMap<>();
    private final Map<String, byte[]> values =
            new ConcurrentHashMap<>();
    private final long lockTimeoutMillis;
    private String txnId;
    private boolean open;

    public PessimisticTransaction(long lockTimeoutMillis) {
        if (lockTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "lock timeout must be positive");
        }
        this.lockTimeoutMillis = lockTimeoutMillis;
    }

    public void begin(String txnId) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
        if (open) {
            throw new IllegalStateException(
                    "transaction already open");
        }
        this.txnId = txnId;
        this.open = true;
    }

    /** 悲观加锁：已被其他事务持有 → 冲突（死锁超时语义）。 */
    public boolean lock(String key, String owner, long nowMillis,
                        long acquiredAtMillis) {
        requireOpen();
        if (nowMillis - acquiredAtMillis > lockTimeoutMillis) {
            throw new IllegalStateException(
                    "lock acquisition timed out");
        }
        String holder = locks.putIfAbsent(key, owner);
        if (holder != null && !holder.equals(owner)) {
            return false;
        }
        return true;
    }

    public void write(String key, byte[] value) {
        requireOpen();
        values.put(key, value);
    }

    public byte[] read(String key) {
        requireOpen();
        return values.get(key);
    }

    public boolean isLocked(String key, String owner) {
        String holder = locks.get(key);
        return holder != null && !holder.equals(owner);
    }

    public void commit() {
        requireOpen();
        open = false;
        locks.clear();
    }

    public void rollback() {
        requireOpen();
        open = false;
        locks.clear();
        values.clear();
    }

    public boolean isOpen() {
        return open;
    }

    public String txnId() {
        return txnId;
    }

    public Set<String> lockedKeys() {
        return Set.copyOf(locks.keySet());
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException(
                    "no active transaction");
        }
    }
}
