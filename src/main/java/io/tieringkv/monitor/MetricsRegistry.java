package io.tieringkv.monitor;

import java.util.concurrent.atomic.LongAdder;

/** 服务端指标（ADR-0034）：连接 / 活跃请求 / QPS / 延迟 / 错误。 */
public final class MetricsRegistry {

    private final LongAdder connections = new LongAdder();
    private final LongAdder activeRequests = new LongAdder();
    private final LongAdder completedCommands = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final long startedAt = System.currentTimeMillis();
    private volatile boolean accepting = true;

    public void connectionOpened() {
        connections.increment();
    }

    public void connectionClosed() {
        connections.decrement();
    }

    public void requestStarted() {
        activeRequests.increment();
    }

    public void requestCompleted(long latencyNanos) {
        activeRequests.decrement();
        completedCommands.increment();
        this.latencyNanos.add(latencyNanos);
    }

    public void error() {
        errors.increment();
    }

    public boolean accepting() {
        return accepting;
    }

    public void stopAccepting() {
        accepting = false;
    }

    public Snapshot snapshot() {
        long completed = completedCommands.sum();
        long elapsedMillis = Math.max(1, System.currentTimeMillis() - startedAt);
        double qps = completed * 1000.0 / elapsedMillis;
        double avgLatencyMs = completed == 0 ? 0 : latencyNanos.sum() / (double) completed / 1_000_000.0;
        return new Snapshot(connections.sum(), activeRequests.sum(), qps, avgLatencyMs, errors.sum());
    }

    public String infoText() {
        Snapshot s = snapshot();
        return String.format(
                "# Server\r\nconnections:%d\r\nactive_requests:%d\r\nqps:%.1f\r\n"
                        + "avg_latency_ms:%.3f\r\nerrors:%d\r\n",
                s.connections(), s.activeRequests(), s.qps(), s.avgLatencyMs(), s.errors());
    }

    public record Snapshot(
            long connections,
            long activeRequests,
            double qps,
            double avgLatencyMs,
            long errors) {
    }
}
