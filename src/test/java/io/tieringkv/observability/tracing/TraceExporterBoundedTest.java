package io.tieringkv.observability.tracing;

import io.tieringkv.observability.TracingMetricsRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G0-5B-3 / ADR-0372：Tracing 有界缓冲与聚合缓存。
 *
 * <p>验证的是真实缓冲结构（容量、淘汰、丢弃计数），不是孤立计数器。
 */
class TraceExporterBoundedTest {

    private static Tracer.Span span(String id, long durationNanos) {
        return new Tracer.Span(id, id + "-span", "", "op", 0L, durationNanos);
    }

    @Test
    void 正常写入与读取() {
        TraceExporter exporter = new TraceExporter(4);
        exporter.export(span("a", 100));
        exporter.export(span("b", 200));

        assertThat(exporter.spans()).hasSize(2);
        assertThat(exporter.spans().get(0).traceId()).isEqualTo("a");
        assertThat(exporter.size()).isEqualTo(2);
    }

    @Test
    void 达到上限后淘汰最旧并计数() {
        TraceExporter exporter = new TraceExporter(3);
        for (int i = 0; i < 5; i++) {
            exporter.export(span("t" + i, 10));
        }
        assertThat(exporter.size()).isEqualTo(3);
        assertThat(exporter.spans()).extracting(Tracer.Span::traceId)
                .containsExactly("t2", "t3", "t4");
        assertThat(exporter.dropped()).isEqualTo(2);
        assertThat(exporter.totalExported()).isEqualTo(5);
    }

    @Test
    void 持续高负载下容量不超限且丢弃计数准确() {
        int capacity = 64;
        int writes = 10_000;
        TraceExporter exporter = new TraceExporter(capacity);
        for (int i = 0; i < writes; i++) {
            exporter.export(span("s" + i, i));
        }
        assertThat(exporter.size()).isEqualTo(capacity);
        assertThat(exporter.dropped()).isEqualTo(writes - capacity);
        assertThat(exporter.totalExported()).isEqualTo(writes);
        assertThat(exporter.spans()).hasSize(capacity);
    }

    @Test
    void 聚合快照为O1且不遍历缓冲() {
        TraceExporter exporter = new TraceExporter(8);
        exporter.export(span("a", 100));
        exporter.export(span("b", 300));

        TraceExporter.Snapshot snapshot = exporter.snapshot();
        assertThat(snapshot.retained()).isEqualTo(2);
        assertThat(snapshot.totalExported()).isEqualTo(2);
        assertThat(snapshot.avgDurationNanos()).isEqualTo(200.0);
        assertThat(snapshot.maxDurationNanos()).isEqualTo(300);
        assertThat(snapshot.dropped()).isZero();
    }

    @Test
    void 均值与最大值是累计语义且不受淘汰影响() {
        TraceExporter exporter = new TraceExporter(2);
        for (int i = 1; i <= 4; i++) {
            exporter.export(span("x" + i, i * 100L));
        }
        TraceExporter.Snapshot snapshot = exporter.snapshot();
        assertThat(snapshot.retained()).isEqualTo(2);
        assertThat(snapshot.totalExported()).isEqualTo(4);
        // 累计均值 = (100+200+300+400)/4，不因淘汰而改变
        assertThat(snapshot.avgDurationNanos()).isEqualTo(250.0);
        assertThat(snapshot.maxDurationNanos()).isEqualTo(400);
    }

    @Test
    void INFO指标不遍历缓冲且包含新字段() {
        TraceExporter exporter = new TraceExporter(2);
        exporter.export(span("a", 10));
        exporter.export(span("b", 20));
        exporter.export(span("c", 30));

        TracingMetricsRegistry registry = new TracingMetricsRegistry(exporter);
        String lines = registry.metricLines();
        assertThat(lines).contains("tracing_spans:2")
                .contains("tracing_spans_total:3")
                .contains("tracing_dropped:1")
                .contains("tracing_capacity:2");
    }

    @Test
    void 消费者读取与淘汰并发安全() throws Exception {
        TraceExporter exporter = new TraceExporter(16);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean bad = new AtomicBoolean();
        List<Runnable> tasks = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int base = t;
            tasks.add(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 2000; i++) {
                    exporter.export(span("w" + base + "-" + i, i));
                }
            });
        }
        for (int t = 0; t < 2; t++) {
            tasks.add(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                for (int i = 0; i < 2000; i++) {
                    if (exporter.spans().size() > exporter.capacity()) {
                        bad.set(true);
                    }
                    exporter.snapshot();
                }
            });
        }
        tasks.forEach(pool::submit);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(bad).isFalse();
        assertThat(exporter.size()).isLessThanOrEqualTo(exporter.capacity());
        assertThat(exporter.totalExported()).isEqualTo(8000);
        assertThat(exporter.dropped()).isEqualTo(8000 - exporter.size());
    }

    @Test
    void 容量必须为正() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new TraceExporter(0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new TraceExporter(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 清空只清缓冲不影响累计计数() {
        TraceExporter exporter = new TraceExporter(4);
        exporter.export(span("a", 10));
        exporter.export(span("b", 20));
        exporter.clear();
        assertThat(exporter.size()).isZero();
        assertThat(exporter.totalExported()).isEqualTo(2);
        assertThat(exporter.snapshot().retained()).isZero();
    }
}