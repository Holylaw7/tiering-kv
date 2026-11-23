package io.tieringkv.observability.tracing;

import io.tieringkv.observability.tracing.Tracer.Span;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 追踪导出（ADR-0154）；F2（ADR-0372）改为有界环形缓冲 + 增量聚合。
 *
 * <p>缓冲区达到容量上限时**淘汰最旧的 Span**，并累计丢弃数；
 * 总量、均值、最大值用增量累加维护，INFO 读取时不再遍历缓冲区。
 */
public final class TraceExporter {

    /** 默认容量：保留最近 1024 条 Span。 */
    public static final int DEFAULT_CAPACITY = 1024;

    /** 聚合快照：INFO 只读这里，字段含义见 ADR-0372。 */
    public record Snapshot(long retained, long totalExported, long dropped,
                           double avgDurationNanos, long maxDurationNanos,
                           long updatedAtMillis) {
    }

    private final int capacity;
    private final Object lock = new Object();
    private final Deque<Span> spans = new ArrayDeque<>();
    private final LongAdder totalExported = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder durationSum = new LongAdder();
    private final AtomicLong maxDuration = new AtomicLong();
    private volatile long updatedAtMillis;

    public TraceExporter() {
        this(DEFAULT_CAPACITY);
    }

    public TraceExporter(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    public void export(Span span) {
        if (span == null) {
            throw new IllegalArgumentException("span required");
        }
        synchronized (lock) {
            if (spans.size() >= capacity) {
                spans.pollFirst();
                dropped.increment();
            }
            spans.addLast(span);
        }
        totalExported.increment();
        durationSum.add(span.durationNanos());
        long current = maxDuration.get();
        while (span.durationNanos() > current && !maxDuration.compareAndSet(current, span.durationNanos())) {
            current = maxDuration.get();
        }
        updatedAtMillis = System.currentTimeMillis();
    }

    /** 当前缓冲区内容（有界副本，按写入顺序）。 */
    public List<Span> spans() {
        synchronized (lock) {
            return new ArrayList<>(spans);
        }
    }

    /** 当前缓冲区中的 Span 数（不是历史总数）。 */
    public int size() {
        synchronized (lock) {
            return spans.size();
        }
    }

    public long totalExported() {
        return totalExported.sum();
    }

    public long dropped() {
        return dropped.sum();
    }

    /** O(1) 聚合快照：INFO 与 Prometheus 共用。 */
    public Snapshot snapshot() {
        long total = totalExported.sum();
        long retained;
        synchronized (lock) {
            retained = spans.size();
        }
        long sum = durationSum.sum();
        double avg = total == 0 ? 0 : sum / (double) total;
        return new Snapshot(retained, total, dropped.sum(), avg, maxDuration.get(), updatedAtMillis);
    }

    public void clear() {
        synchronized (lock) {
            spans.clear();
        }
    }

    public String toJson() {
        String body = spans().stream()
                .map(span -> "{"
                        + json("traceId", span.traceId()) + ","
                        + json("spanId", span.spanId()) + ","
                        + json("parentSpanId", span.parentSpanId())
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
