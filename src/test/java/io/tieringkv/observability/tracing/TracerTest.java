package io.tieringkv.observability.tracing;

import io.tieringkv.observability.tracing.Tracer.Context;
import io.tieringkv.observability.tracing.Tracer.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分布式追踪（ADR-0154）：跨度生命周期 + 跨 RPC 传播。 */
class TracerTest {

    @Test
    void startEndCreatesSpan() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context context = tracer.start("GET");
        tracer.end(context);
        assertThat(exporter.spans()).hasSize(1);
        Span span = exporter.spans().get(0);
        assertThat(span.operation()).isEqualTo("GET");
        assertThat(span.parentSpanId()).isEmpty();
        assertThat(span.durationNanos()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void nestedSpansParentChain() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context root = tracer.start("txn");
        Context child = tracer.start("prewrite");
        tracer.end(child);
        tracer.end(root);
        Span childSpan = exporter.spans().get(0);
        Span rootSpan = exporter.spans().get(1);
        assertThat(rootSpan.parentSpanId()).isEmpty();
        assertThat(childSpan.parentSpanId())
                .isEqualTo(rootSpan.spanId());
        assertThat(childSpan.traceId()).isEqualTo(rootSpan.traceId());
    }

    @Test
    void injectExtractRoundTrip() {
        Tracer tracer = tracer(new TraceExporter(), 1.0);
        Context context = tracer.start("op");
        String header = tracer.inject(context);
        Context extracted = tracer.extract(header);
        assertThat(extracted).isEqualTo(context);
        tracer.end(context);
    }

    @Test
    void startWithExtractedParent() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context parent = tracer.start("rpc-call");
        String header = tracer.inject(parent);
        Context remote = tracer.extract(header);
        Context child = tracer.start("handler", remote);
        tracer.end(child);
        tracer.end(parent);
        Span childSpan = exporter.spans().get(0);
        assertThat(childSpan.traceId()).isEqualTo(remote.traceId());
        assertThat(childSpan.parentSpanId())
                .isEqualTo(remote.spanId());
    }

    @Test
    void blankOperationRejected() {
        Tracer tracer = tracer(new TraceExporter(), 1.0);
        assertThatThrownBy(() -> tracer.start(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracer.start("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullOperationRejected() {
        Tracer tracer = tracer(new TraceExporter(), 1.0);
        assertThatThrownBy(() -> tracer.start(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsampledSpansNotExported() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 0.0);
        Context context = tracer.start("op");
        tracer.end(context);
        assertThat(exporter.spans()).isEmpty();
    }

    @Test
    void endWithoutStartRejected() {
        Tracer tracer = tracer(new TraceExporter(), 1.0);
        assertThatThrownBy(() -> tracer.end(
                new Context("t", "s")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sequentialSpansIndependent() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context first = tracer.start("a");
        tracer.end(first);
        Context second = tracer.start("b");
        tracer.end(second);
        assertThat(exporter.spans()).hasSize(2);
        assertThat(exporter.spans().get(1).parentSpanId())
                .isEmpty();
    }

    @Test
    void parallelTracesIsolated() throws Exception {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10; i++) {
                    Context context = tracer.start("op");
                    tracer.end(context);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(exporter.spans()).hasSize(40);
    }

    @Test
    void invalidHeaderRejected() {
        Tracer tracer = tracer(new TraceExporter(), 1.0);
        assertThatThrownBy(() -> tracer.extract("no-colon"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracer.extract(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tracer.extract(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void injectNullRejected() {
        Tracer tracer = tracer(new TraceExporter(), 1.0);
        assertThatThrownBy(() -> tracer.inject(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void spanCarriesTraceAndSpanIds() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context context = tracer.start("op");
        tracer.end(context);
        Span span = exporter.spans().get(0);
        assertThat(span.traceId()).isEqualTo(context.traceId());
        assertThat(span.spanId()).isEqualTo(context.spanId());
    }

    @Test
    void childEndsBeforeParent() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context parent = tracer.start("parent");
        Context child = tracer.start("child");
        tracer.end(child);
        Context sibling = tracer.start("sibling");
        tracer.end(sibling);
        tracer.end(parent);
        assertThat(exporter.spans().get(2).parentSpanId()).isEmpty();
        assertThat(exporter.spans().get(1).parentSpanId())
                .isEqualTo(parent.spanId());
    }

    @ParameterizedTest(name = "spans {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedSpanCounts(int count) {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        for (int i = 0; i < count; i++) {
            Context context = tracer.start("op" + i);
            tracer.end(context);
        }
        assertThat(exporter.spans()).hasSize(count);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void parameterizedSamplingRates(double rate) {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, rate);
        for (int i = 0; i < 100; i++) {
            Context context = tracer.start("op" + i);
            tracer.end(context);
        }
        if (rate == 0.0) {
            assertThat(exporter.spans()).isEmpty();
        } else if (rate == 1.0) {
            assertThat(exporter.spans()).hasSize(100);
        }
    }

    @Test
    void nestedDepthPreservedAcrossEnds() {
        TraceExporter exporter = new TraceExporter();
        Tracer tracer = tracer(exporter, 1.0);
        Context a = tracer.start("a");
        Context b = tracer.start("b");
        Context c = tracer.start("c");
        tracer.end(c);
        tracer.end(b);
        Context d = tracer.start("d");
        tracer.end(d);
        tracer.end(a);
        Span dSpan = exporter.spans().get(2);
        assertThat(dSpan.parentSpanId()).isEqualTo(a.spanId());
    }

    private static Tracer tracer(TraceExporter exporter,
                                 double rate) {
        return new Tracer(new TraceSampler(rate), exporter);
    }
}
