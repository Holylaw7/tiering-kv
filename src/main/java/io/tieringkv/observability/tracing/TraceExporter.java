package io.tieringkv.observability.tracing;

import io.tieringkv.observability.tracing.Tracer.Span;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** 追踪导出（ADR-0154）：JSON 导出 + 内存收集。 */
public final class TraceExporter {

    private final List<Span> spans = new CopyOnWriteArrayList<>();

    public void export(Span span) {
        if (span == null) {
            throw new IllegalArgumentException("span required");
        }
        spans.add(span);
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

    public int size() {
        return spans.size();
    }

    public void clear() {
        spans.clear();
    }

    public String toJson() {
        String body = spans.stream()
                .map(span -> "{"
                        + json("traceId", span.traceId()) + ","
                        + json("spanId", span.spanId()) + ","
                        + json("parentSpanId", span.parentSpanId())
                        + ","
                        + json("operation", span.operation()) + ","
                        + json("durationNanos", String.valueOf(
                        span.durationNanos())) + "}")
                .collect(Collectors.joining(","));
        return "[" + body + "]";
    }

    private static String json(String key, String value) {
        return "\"" + escape(key) + "\":\""
                + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
