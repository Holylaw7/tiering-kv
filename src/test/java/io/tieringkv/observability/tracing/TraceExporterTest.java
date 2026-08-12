package io.tieringkv.observability.tracing;

import io.tieringkv.observability.tracing.Tracer.Span;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 追踪导出（ADR-0154）：收集 + JSON。 */
class TraceExporterTest {

    @Test
    void exportAddsSpan() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(span("t1"));
        assertThat(exporter.spans()).hasSize(1);
    }

    @Test
    void spansAreCopied() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(span("t1"));
        java.util.List<Span> view = exporter.spans();
        exporter.export(span("t2"));
        assertThat(view).hasSize(1);
        assertThat(exporter.size()).isEqualTo(2);
    }

    @Test
    void clearEmpties() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(span("t1"));
        exporter.clear();
        assertThat(exporter.size()).isZero();
        assertThat(exporter.toJson()).isEqualTo("[]");
    }

    @Test
    void toJsonContainsFields() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(new Span("t1", "s1", "", "GET",
                1_000, 500));
        String json = exporter.toJson();
        assertThat(json).startsWith("[{").endsWith("}]")
                .contains("\"traceId\":\"t1\"")
                .contains("\"operation\":\"GET\"")
                .contains("\"durationNanos\":\"500\"");
    }

    @Test
    void toJsonIncludesParent() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(new Span("t1", "s2", "s1", "op",
                1, 2));
        assertThat(exporter.toJson())
                .contains("\"parentSpanId\":\"s1\"");
    }

    @Test
    void emptyToJson() {
        assertThat(new TraceExporter().toJson()).isEqualTo("[]");
    }

    @Test
    void nullSpanRejected() {
        assertThatThrownBy(() -> new TraceExporter().export(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonEscapesValues() {
        TraceExporter exporter = new TraceExporter();
        exporter.export(new Span("t\"1", "s1", "", "say \"hi\"",
                1, 1));
        String json = exporter.toJson();
        assertThat(json).contains("t\\\"1")
                .doesNotContain("t\"1");
    }

    @ParameterizedTest(name = "spans {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedExport(int count) {
        TraceExporter exporter = new TraceExporter();
        for (int i = 0; i < count; i++) {
            exporter.export(span("t" + i));
        }
        assertThat(exporter.size()).isEqualTo(count);
    }

    @Test
    void concurrentExportSafe() throws Exception {
        TraceExporter exporter = new TraceExporter();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    exporter.export(span("t"));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(exporter.size()).isEqualTo(400);
    }

    private static Span span(String traceId) {
        return new Span(traceId, "s", "", "op", 1, 1);
    }
}
