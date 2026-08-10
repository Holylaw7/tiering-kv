package io.tieringkv.cluster.migration.parallel;

/** 迁移限速器（Phase 18）：字节/秒令牌桶，0 表示不限速。 */
final class ByteRateLimiter {

    private final long bytesPerSec;
    private long available;
    private long lastNanos = System.nanoTime();

    ByteRateLimiter(long bytesPerSec) {
        this.bytesPerSec = Math.max(0, bytesPerSec);
    }

    synchronized void consume(long bytes) {
        if (bytesPerSec <= 0) {
            return;
        }
        long now = System.nanoTime();
        available += (now - lastNanos) * bytesPerSec / 1_000_000_000L;
        available = Math.min(available, bytesPerSec * 2);
        lastNanos = now;
        if (available < bytes) {
            long deficit = bytes - available;
            long sleepNanos = deficit * 1_000_000_000L / bytesPerSec;
            available = 0;
            try {
                Thread.sleep(Math.max(1, sleepNanos / 1_000_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            available -= bytes;
        }
    }
}
