package io.tieringkv.transaction.lifecycle;

import io.tieringkv.mvcc.Transaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事务生命周期管理（ADR-0088）：ACTIVE → PREWRITE → COMMITTED /
 * ROLLED_BACK / EXPIRED；支持 TTL 与心跳续约。
 */
public final class TransactionLifecycleManager {

    public static final class TxnHandle {
        private final Transaction txn;
        private final long startTimeMillis;
        private volatile long lastHeartbeatMillis;
        private final long ttlMillis;
        private final long maxDurationMillis;
        private volatile TxnLifecycleState state;

        private TxnHandle(Transaction txn, long startTimeMillis,
                          long lastHeartbeatMillis, long ttlMillis,
                          long maxDurationMillis, TxnLifecycleState state) {
            this.txn = txn;
            this.startTimeMillis = startTimeMillis;
            this.lastHeartbeatMillis = lastHeartbeatMillis;
            this.ttlMillis = ttlMillis;
            this.maxDurationMillis = maxDurationMillis;
            this.state = state;
        }

        public Transaction txn() {
            return txn;
        }

        public long startTimeMillis() {
            return startTimeMillis;
        }

        public long lastHeartbeatMillis() {
            return lastHeartbeatMillis;
        }

        public long ttlMillis() {
            return ttlMillis;
        }

        public long maxDurationMillis() {
            return maxDurationMillis;
        }

        public TxnLifecycleState state() {
            return state;
        }
    }

    private final Map<String, TxnHandle> txns = new ConcurrentHashMap<>();

    public TxnHandle begin(Transaction txn, long ttlMillis,
                           long maxDurationMillis) {
        long now = System.currentTimeMillis();
        TxnHandle handle = new TxnHandle(txn, now, now, ttlMillis,
                maxDurationMillis, TxnLifecycleState.ACTIVE);
        txns.put(txn.txnId(), handle);
        return handle;
    }

    public void markPrewrite(String txnId) {
        update(txnId, TxnLifecycleState.PREWRITE);
    }

    public void markCommitted(String txnId) {
        update(txnId, TxnLifecycleState.COMMITTED);
    }

    public void markRolledBack(String txnId) {
        update(txnId, TxnLifecycleState.ROLLED_BACK);
    }

    public void markExpired(String txnId) {
        update(txnId, TxnLifecycleState.EXPIRED);
    }

    public void heartbeat(String txnId, long nowMillis) {
        TxnHandle handle = txns.get(txnId);
        if (handle != null) {
            handle.lastHeartbeatMillis = nowMillis;
        }
    }

    public TxnHandle get(String txnId) {
        return txns.get(txnId);
    }

    public int activeCount() {
        int count = 0;
        for (TxnHandle handle : txns.values()) {
            if (handle.state() == TxnLifecycleState.ACTIVE
                    || handle.state() == TxnLifecycleState.PREWRITE) {
                count++;
            }
        }
        return count;
    }

    public int preparedCount() {
        int count = 0;
        for (TxnHandle handle : txns.values()) {
            if (handle.state() == TxnLifecycleState.PREWRITE) {
                count++;
            }
        }
        return count;
    }

    /** 超过 TTL（无心跳）或超过最大时长的活跃事务。 */
    public List<TxnHandle> expiredCandidates(long nowMillis) {
        List<TxnHandle> expired = new ArrayList<>();
        for (TxnHandle handle : txns.values()) {
            if (handle.state() != TxnLifecycleState.ACTIVE
                    && handle.state() != TxnLifecycleState.PREWRITE) {
                continue;
            }
            boolean ttlExpired = nowMillis - handle.lastHeartbeatMillis()
                    > handle.ttlMillis();
            boolean durationExpired = nowMillis - handle.startTimeMillis()
                    > handle.maxDurationMillis();
            if (ttlExpired || durationExpired) {
                expired.add(handle);
            }
        }
        return expired;
    }

    public int size() {
        return txns.size();
    }

    private void update(String txnId, TxnLifecycleState state) {
        TxnHandle handle = txns.get(txnId);
        if (handle != null) {
            handle.state = state;
        }
    }
}
