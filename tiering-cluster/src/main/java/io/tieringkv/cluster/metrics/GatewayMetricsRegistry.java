package io.tieringkv.cluster.metrics;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** 网关指标（ADR-0070）：connection / qps / latency。 */
public final class GatewayMetricsRegistry {

    private final LongAdder connections = new LongAdder();
    private final LongAdder requests = new LongAdder();
    private final LongAdder latencyNanos = new LongAdder();
    private final LongAdder transactions = new LongAdder();
    private final LongAdder transactionLatencyNanos = new LongAdder();
    private final long startedAt = System.currentTimeMillis();

    public void connectionOpened() {
        connections.increment();
    }

    public void connectionClosed() {
        connections.decrement();
    }

    public void recordRequest(long latencyNanos) {
        requests.increment();
        this.latencyNanos.add(latencyNanos);
    }

    /** 记录一次自动事务（SET/DEL/MSET）延迟（ADR-0079）。 */
    public void recordTransaction(long latencyNanos) {
        transactions.increment();
        transactionLatencyNanos.add(latencyNanos);
    }

    public Snapshot snapshot() {
        long count = requests.sum();
        long txnCount = transactions.sum();
        double qps = count * 1000.0
                / Math.max(1, System.currentTimeMillis() - startedAt);
        double avgMs = count == 0 ? 0
                : latencyNanos.sum() / (double) count / 1_000_000.0;
        double txnAvgMs = txnCount == 0 ? 0
                : transactionLatencyNanos.sum() / (double) txnCount / 1_000_000.0;
        return new Snapshot(connections.sum(), qps, avgMs, txnCount, txnAvgMs);
    }

    public String sectionText() {
        return "# Gateway\r\n" + metricLines();
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "gateway_connections:%d\r\n"
                        + "gateway_qps:%.1f\r\n"
                        + "gateway_avg_latency_ms:%.3f\r\n"
                        + "redis_txn_total:%d\r\n"
                        + "redis_txn_latency_ms:%.3f\r\n",
                s.connections(), s.qps(), s.avgLatencyMs(),
                s.transactionTotal(), s.transactionLatencyMs());
    }

    public record Snapshot(long connections, double qps, double avgLatencyMs,
                           long transactionTotal, double transactionLatencyMs) {
    }
}
