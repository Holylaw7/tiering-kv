package io.tieringkv.observability;

import io.tieringkv.observability.tracing.TraceExporter;
import io.tieringkv.observability.tracing.Tracer;

import java.util.List;
import java.util.Locale;

/**
 * 追踪指标（ADR-0345）：从 TraceExporter 统计 span 数/平均/最大延迟。
 * 数据源唯一（exporter.spans()），INFO 与 Prometheus 同 snapshot。
 */
public final class TracingMetricsRegistry {

    private final TraceExporter exporter;

    public TracingMetricsRegistry(TraceExporter exporter) {
        this.exporter = exporter;
    }

    public Snapshot snapshot() {
        List<Tracer.Span> spans = exporter == null
                ? List.of() : exporter.spans();
        long total = spans.size();
        long sum = spans.stream()
                .mapToLong(Tracer.Span::durationNanos).sum();
        long max = spans.stream()
                .mapToLong(Tracer.Span::durationNanos)
                .max().orElse(0);
        long avg = total == 0 ? 0 : sum / total;
        return new Snapshot(total, avg, max);
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "tracing_spans:%d\r\n"
                        + "tracing_avg_duration_nanos:%d\r\n"
                        + "tracing_max_duration_nanos:%d\r\n",
                s.spans(), s.avgDurationNanos(), s.maxDurationNanos());
    }

    public record Snapshot(long spans, long avgDurationNanos,
                           long maxDurationNanos) {
    }
}
