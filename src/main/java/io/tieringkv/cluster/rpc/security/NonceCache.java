package io.tieringkv.cluster.rpc.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 防重放 nonce 缓存（ADR-0051）：带过期时间的有界集合。 */
public final class NonceCache {

    private final Map<String, Long> nonces = new ConcurrentHashMap<>();
    private final long entryTtlMillis;
    private final int maxEntries;

    public NonceCache(long entryTtlMillis, int maxEntries) {
        this.entryTtlMillis = entryTtlMillis;
        this.maxEntries = maxEntries;
    }

    public static NonceCache defaults() {
        return new NonceCache(60_000, 100_000);
    }

    public boolean tryConsume(String clientId, String nonce, long timestamp,
                              long windowMillis, long now) {
        if (Math.abs(now - timestamp) > windowMillis) {
            return false;
        }
        String key = clientId + "|" + nonce;
        Long previous = nonces.putIfAbsent(key, now + entryTtlMillis);
        if (previous != null) {
            return false; // 重放
        }
        if (nonces.size() > maxEntries) {
            nonces.entrySet().removeIf(e -> e.getValue() < now);
        }
        return true;
    }

    public int size() {
        return nonces.size();
    }
}
