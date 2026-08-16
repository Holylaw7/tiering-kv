package io.tieringkv.observability.tracing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ThreadLocalRandom;

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
        if (!active.context().equals(context)) {
            throw new IllegalStateException(
                    "span end mismatch: expected "
                            + active.context().spanId()
                            + " but got " + context.spanId());
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

    /**
     * W3C traceparent（ADR-0345，OTel 兼容，零依赖）：32hex traceId
     * + 16hex spanId。父上下文为空时生成新 trace。
     */
    public Context startW3c(String operation, Context parent) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException(
                    "operation required");
        }
        String traceId = parent == null ? randomHex(16) : parent.traceId();
        Context context = new Context(traceId, randomHex(8));
        stack.get().push(new Active(operation,
                System.nanoTime(), context, parent));
        return context;
    }

    /** 注入 W3C traceparent：{@code 00-<trace32>-<span16>-01}。 */
    public String injectTraceparent(Context context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "context required");
        }
        return "00-" + context.traceId() + "-"
                + context.spanId() + "-01";
    }

    /** 提取 W3C traceparent：校验 version 与 id 长度，非法抛异常。 */
    public Context extractTraceparent(String header) {
        if (header == null || header.isBlank()) {
            throw new IllegalArgumentException(
                    "invalid traceparent");
        }
        String[] parts = header.split("-");
        if (parts.length != 4 || !"00".equals(parts[0])
                || parts[1].length() != 32
                || parts[2].length() != 16) {
            throw new IllegalArgumentException(
                    "invalid traceparent");
        }
        return new Context(parts[1], parts[2]);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(Character.forDigit(
                    (b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
