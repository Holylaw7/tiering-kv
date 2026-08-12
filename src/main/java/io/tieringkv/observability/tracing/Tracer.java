package io.tieringkv.observability.tracing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/** 分布式追踪（ADR-0154）：Span/Trace 上下文 + 跨 RPC 传播。 */
public final class Tracer {

    /** 追踪跨度。 */
    public record Span(String traceId, String spanId,
                       String parentSpanId, String operation,
                       long startNanos, long durationNanos) {
    }

    /** 追踪上下文：traceId + 当前 spanId。 */
    public record Context(String traceId, String spanId) {
    }

    private record Active(String operation, long startNanos,
                          Context context, Context parent) {
    }

    private final TraceSampler sampler;
    private final TraceExporter exporter;
    private final AtomicLong seq = new AtomicLong();
    private final ThreadLocal<Deque<Active>> stack =
            ThreadLocal.withInitial(ArrayDeque::new);

    public Tracer(TraceSampler sampler, TraceExporter exporter) {
        this.sampler = sampler;
        this.exporter = exporter;
    }

    /** 开始跨度：继承当前线程上下文作为父跨度。 */
    public Context start(String operation) {
        Active current = stack.get().peek();
        return start(operation,
                current == null ? null : current.context());
    }

    /** 开始跨度：显式父上下文（跨 RPC 传播后使用）。 */
    public Context start(String operation, Context parent) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException(
                    "operation required");
        }
        String traceId = parent == null
                ? "trace-" + seq.incrementAndGet()
                : parent.traceId();
        Context context = new Context(traceId,
                "span-" + seq.incrementAndGet());
        stack.get().push(new Active(operation,
                System.nanoTime(), context, parent));
        return context;
    }

    /** 结束跨度：出栈并导出（未采样不导出）。 */
    public void end(Context context) {
        Deque<Active> activeStack = stack.get();
        Active active = activeStack.poll();
        if (active == null) {
            throw new IllegalStateException(
                    "no active span to end");
        }
        long duration = System.nanoTime() - active.startNanos();
        if (sampler.sample(context.traceId())) {
            exporter.export(new Span(context.traceId(),
                    context.spanId(),
                    active.parent() == null ? ""
                            : active.parent().spanId(),
                    active.operation(), active.startNanos(),
                    Math.max(0, duration)));
        }
    }

    /** 注入：traceId:spanId 传播头。 */
    public String inject(Context context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "context required");
        }
        return context.traceId() + ":" + context.spanId();
    }

    /** 提取：解析传播头为上下文。 */
    public Context extract(String header) {
        if (header == null || header.isBlank()
                || !header.contains(":")) {
            throw new IllegalArgumentException(
                    "invalid trace header");
        }
        String[] parts = header.split(":", 2);
        return new Context(parts[0], parts[1]);
    }
}
