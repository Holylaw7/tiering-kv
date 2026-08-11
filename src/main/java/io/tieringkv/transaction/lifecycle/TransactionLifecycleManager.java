package io.tieringkv.transaction.lifecycle;

import io.tieringkv.mvcc.Transaction;
import io.tieringkv.transaction.metadata.TransactionMetadataService;

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
    private final TransactionMetadataService metadata;

    public TransactionLifecycleManager() {
        this(null);
    }

    public TransactionLifecycleManager(TransactionMetadataService metadata) {
        this.metadata = metadata;
    }

    public TxnHandle begin(Transaction txn, long ttlMillis,
                           long maxDurationMillis) {
        long now = System.currentTimeMillis();
        TxnHandle handle = new TxnHandle(txn, now, now, ttlMillis,
                maxDurationMillis, TxnLifecycleState.ACTIVE);
        txns.put(txn.txnId(), handle);
        persist(txn.txnId(), txn.startTS(), TxnLifecycleState.ACTIVE,
                now + ttlMillis);
        return handle;
    }

    public void markPrewrite(String txnId) {
        update(txnId, TxnLifecycleState.PREWRITE);
        persist(txnId, get(txnId) == null ? 0 : get(txnId).txn().startTS(),
                TxnLifecycleState.PREWRITE,
                get(txnId) == null ? 0 : get(txnId).lastHeartbeatMillis()
                        + get(txnId).ttlMillis());
    }

    public void markCommitted(String txnId) {
        update(txnId, TxnLifecycleState.COMMITTED);
        persistStateOnly(txnId, TxnLifecycleState.COMMITTED);
    }

    public void markRolledBack(String txnId) {
        update(txnId, TxnLifecycleState.ROLLED_BACK);
        persistStateOnly(txnId, TxnLifecycleState.ROLLED_BACK);
    }

    public void markExpired(String txnId) {
        update(txnId, TxnLifecycleState.EXPIRED);
        persistStateOnly(txnId, TxnLifecycleState.EXPIRED);
    }

    public void heartbeat(String txnId, long nowMillis) {
        TxnHandle handle = txns.get(txnId);
        if (handle != null) {
            handle.lastHeartbeatMillis = nowMillis;
            persist(handle.txn().txnId(), handle.txn().startTS(),
                    handle.state(), nowMillis + handle.ttlMillis());
        }
    }

    /** 从元数据 Raft 恢复生命周期（ADR-0091）：重建句柄并 abort 过期。 */
    public int recoverFromMetadata(long ttlMillis, long maxDurationMillis) {
        int restored = 0;
        if (metadata == null) {
            return 0;
        }
        long now = System.currentTimeMillis();
        for (TxnLifecycleRecord record : metadata.lifecycleSnapshot().values()) {
            if (record.state() == TxnLifecycleState.COMMITTED
                    || record.state() == TxnLifecycleState.ROLLED_BACK
                    || record.state() == TxnLifecycleState.EXPIRED) {
                continue;
            }
            Transaction txn = new Transaction(record.txnId(), record.startTS());
            TxnHandle handle = new TxnHandle(txn, record.startTS(),
                    now, ttlMillis, maxDurationMillis, record.state());
            txns.put(record.txnId(), handle);
            if (record.expireAtMillis() < now) {
                handle.state = TxnLifecycleState.EXPIRED;
            }
            restored++;
        }
        return restored;
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

    private void persist(String txnId, long startTS,
                         TxnLifecycleState state, long expireAtMillis) {
        if (metadata != null) {
            try {
                metadata.recordLifecycle(txnId, startTS, state,
                        expireAtMillis).join();
            } catch (RuntimeException ignored) {
                // 生命周期持久化尽力而为；内存状态仍可用
            }
        }
    }

    private void persistStateOnly(String txnId, TxnLifecycleState state) {
        TxnHandle handle = txns.get(txnId);
        if (handle != null) {
            persist(txnId, handle.txn().startTS(), state,
                    System.currentTimeMillis() + handle.ttlMillis());
        }
    }
}
