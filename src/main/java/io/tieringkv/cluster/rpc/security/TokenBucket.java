package io.tieringkv.cluster.rpc.security;

/** 令牌桶限流（ADR-0046）：容量 = QPS，匀速补充。 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos = System.nanoTime();

    public TokenBucket(int qps) {
        if (qps <= 0) {
            throw new IllegalArgumentException("qps must be positive");
        }
        this.capacity = qps;
        this.refillPerSecond = qps;
        this.tokens = qps;
    }

    public synchronized boolean tryAcquire() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        lastRefillNanos = now;
        tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }
}
