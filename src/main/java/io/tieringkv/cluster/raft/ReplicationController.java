package io.tieringkv.cluster.raft;

/**
 * 自适应复制控制器（ADR-0050）：按 pending 条数、网络 RTT（EMA）与
 * follower 滞后动态输出 batchSize 与 flushInterval：
 * 低延迟场景 batch=16/flush=1ms，高吞吐场景 batch=512/flush=10ms。
 */
public final class ReplicationController {

    public static final int MIN_BATCH = 16;
    public static final int MAX_BATCH = 512;
    public static final long MIN_FLUSH_MILLIS = 1;
    public static final long MAX_FLUSH_MILLIS = 10;

    private final double emaAlpha;
    private double emaRttNanos;
    private volatile long pendingEntries;
    private volatile long followerLag;

    public ReplicationController(double emaAlpha) {
        this.emaAlpha = emaAlpha;
    }

    public static ReplicationController defaults() {
        return new ReplicationController(0.2);
    }

    public void recordRttNanos(long rttNanos) {
        emaRttNanos = emaRttNanos == 0
                ? rttNanos : emaAlpha * rttNanos + (1 - emaAlpha) * emaRttNanos;
    }

    public void setPendingEntries(long pending) {
        this.pendingEntries = Math.max(0, pending);
    }

    public void setFollowerLag(long lag) {
        this.followerLag = Math.max(0, lag);
    }

    public int batchSize() {
        double pressure = Math.min(1.0, pendingEntries / 512.0);
        double rttFactor = Math.min(1.0, emaRttNanos / 1_000_000.0 / 5.0); // 5ms 归一
        double combined = Math.min(1.0, Math.max(pressure, rttFactor));
        double size = MIN_BATCH + (MAX_BATCH - MIN_BATCH) * combined;
        return (int) Math.max(MIN_BATCH, Math.min(MAX_BATCH, size));
    }

    public long flushIntervalMillis() {
        double pressure = Math.min(1.0, pendingEntries / 512.0);
        double lagFactor = Math.min(1.0, followerLag / 1024.0);
        double interval = MAX_FLUSH_MILLIS
                - (MAX_FLUSH_MILLIS - MIN_FLUSH_MILLIS)
                * Math.max(pressure, lagFactor);
        return (long) Math.max(MIN_FLUSH_MILLIS, Math.min(MAX_FLUSH_MILLIS, interval));
    }

    public double rttNanos() {
        return emaRttNanos;
    }
}
