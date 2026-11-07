package io.tieringkv.observability;

import io.tieringkv.observability.tracing.TraceExporter;
import io.tieringkv.observability.tracing.Tracer;

import java.util.List;
import java.util.Locale;

/**
 * 追踪指标（ADR-0345 / F2 ADR-0372）：只读 TraceExporter 的 O(1) 聚合快照，
 * 不遍历缓冲区。
 */
public final class TracingMetricsRegistry {

    private final TraceExporter exporter;

    public TracingMetricsRegistry(TraceExporter exporter) {
        this.exporter = exporter;
    }

    public Snapshot snapshot() {
        if (exporter == null) {
            return new Snapshot(0, 0, 0, 0, 0, 0);
        }
        TraceExporter.Snapshot s = exporter.snapshot();
        return new Snapshot(s.retained(), s.totalExported(), s.dropped(), exporter.capacity(),
                (long) s.avgDurationNanos(), s.maxDurationNanos());
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "tracing_spans:%d\r\n"
                        + "tracing_spans_total:%d\r\n"
                        + "tracing_dropped:%d\r\n"
                        + "tracing_capacity:%d\r\n"
                        + "tracing_avg_duration_nanos:%d\r\n"
                        + "tracing_max_duration_nanos:%d\r\n",
                s.spans(), s.total(), s.dropped(), s.capacity(),
                s.avgDurationNanos(), s.maxDurationNanos());
    }

    /** spans 为**当前缓冲区保留数**；total 为累计导出数。 */
    public record Snapshot(long spans, long total, long dropped, long capacity,
                           long avgDurationNanos, long maxDurationNanos) {
    }
}