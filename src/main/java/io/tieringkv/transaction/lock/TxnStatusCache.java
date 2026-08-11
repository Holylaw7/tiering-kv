package io.tieringkv.transaction.lock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 事务状态缓存（ADR-0089）：txnId → 解析结果，带 TTL 防膨胀。 */
public final class TxnStatusCache {

    public enum Status {
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }

    private record Entry(Status status, long expiresAtMillis) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public TxnStatusCache(long ttlMillis) {
        this.ttlMillis = ttlMillis;
    }

    public Status get(String txnId, long nowMillis) {
        Entry entry = entries.get(txnId);
        if (entry == null || entry.expiresAtMillis() < nowMillis) {
            return null;
        }
        return entry.status();
    }

    public void set(String txnId, Status status, long nowMillis) {
        entries.put(txnId, new Entry(status, nowMillis + ttlMillis));
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }
}
