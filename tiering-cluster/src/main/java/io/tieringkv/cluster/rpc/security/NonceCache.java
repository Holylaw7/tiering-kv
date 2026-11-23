package io.tieringkv.cluster.rpc.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 防重放 nonce 缓存（ADR-0051）：带过期时间的有界集合。 */
public final class NonceCache {

    private record NonceKey(String clientId, String nonce) { }

    private final Map<NonceKey, Long> nonces = new ConcurrentHashMap<>();
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
        NonceKey key = new NonceKey(clientId, nonce);
        long expiry = now + entryTtlMillis;
        Long previous = nonces.putIfAbsent(key, expiry);
        if (previous != null) {
            if (previous >= now || !nonces.replace(key, previous, expiry)) {
                return false; // 重放或并发竞争
            }
        }
        if (nonces.size() > maxEntries) {
            nonces.entrySet().removeIf(e -> e.getValue() < now);
            if (nonces.size() > maxEntries) {
                java.util.Iterator<NonceKey> it = nonces.keySet().iterator();
                while (nonces.size() > maxEntries && it.hasNext()) {
                    NonceKey candidate = it.next();
                    if (!candidate.equals(key)) {
                        it.remove();
                    }
                }
            }
        }
        return true;
    }

    public int size() {
        return nonces.size();
    }
}
