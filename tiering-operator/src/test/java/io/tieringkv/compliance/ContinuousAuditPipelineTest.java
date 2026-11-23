package io.tieringkv.compliance;

import io.tieringkv.compliance.ComplianceReport.Severity;
import io.tieringkv.compliance.ComplianceReport.Violation;
import io.tieringkv.compliance.ContinuousAuditPipeline.AuditRun;
import io.tieringkv.compliance.RegulationMapper.Control;
import io.tieringkv.compliance.RegulationVersion.Version;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 持续审计流水线（ADR-0159）：版本评估 + 导出 + 记录。 */
class ContinuousAuditPipelineTest {

    private RegulationVersionStore store;
    private ContinuousAuditPipeline pipeline;

    @BeforeEach
    void setUp() {
        store = new RegulationVersionStore();
        store.register(new Version("GDPR", "v1", 1000,
                Set.of(new Control("g1", "residency", true),
                        new Control("g2", "audit", false))));
        pipeline = new ContinuousAuditPipeline(store,
                new AuditExporter());
    }

    @Test
    void evaluateRecordsRun() {
        AuditRun run = pipeline.evaluate("GDPR", 1500,
                controls -> reportFor(controls, false));
        assertThat(run.regulation()).isEqualTo("GDPR");
        assertThat(run.versionId()).isEqualTo("v1");
        assertThat(run.violations()).isEqualTo(1);
        assertThat(pipeline.runCount()).isEqualTo(1);
    }

    @Test
    void evaluateUsesEffectiveVersion() {
        store.register(new Version("GDPR", "v2", 2000,
                Set.of(new Control("g1", "residency", true))));
        AuditRun run = pipeline.evaluate("GDPR", 2500,
                controls -> reportFor(controls, false));
        assertThat(run.versionId()).isEqualTo("v2");
    }

    @Test
    void evaluateExportContainsViolations() {
        AuditRun run = pipeline.evaluate("GDPR", 1500,
                controls -> reportFor(controls, false));
        assertThat(run.exportJson()).contains("\"violations\"")
                .contains("\"count\":1");
    }

    @Test
    void evaluateNoViolations() {
        AuditRun run = pipeline.evaluate("GDPR", 1500,
                controls -> new ComplianceReport());
        assertThat(run.violations()).isZero();
        assertThat(run.exportJson()).contains("\"count\":0");
    }

    @Test
    void noEffectiveVersionRejected() {
        assertThatThrownBy(() -> pipeline.evaluate("GDPR", 999,
                controls -> new ComplianceReport()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRegulationRejected() {
        assertThatThrownBy(() -> pipeline.evaluate("SOX", 1500,
                controls -> new ComplianceReport()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRegulationRejected() {
        assertThatThrownBy(() -> pipeline.evaluate("", 1500,
                controls -> new ComplianceReport()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullEvaluatorRejected() {
        assertThatThrownBy(() -> pipeline.evaluate(
                "GDPR", 1500, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runsForFiltersByRegulation() {
        pipeline.evaluate("GDPR", 1500,
                controls -> new ComplianceReport());
        store.register(new Version("SOC2", "s1", 1000, Set.of()));
        pipeline.evaluate("SOC2", 1500,
                controls -> new ComplianceReport());
        assertThat(pipeline.runsFor("GDPR")).hasSize(1);
        assertThat(pipeline.runsFor("SOC2")).hasSize(1);
        assertThat(pipeline.runCount()).isEqualTo(2);
    }

    @Test
    void runsAreCopied() {
        pipeline.evaluate("GDPR", 1500,
                controls -> new ComplianceReport());
        java.util.List<AuditRun> view = pipeline.runs();
        pipeline.evaluate("GDPR", 1600,
                controls -> new ComplianceReport());
        assertThat(view).hasSize(1);
        assertThat(pipeline.runCount()).isEqualTo(2);
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {1000, 2000, 10_000})
    void parameterizedEffectiveTimes(long time) {
        AuditRun run = pipeline.evaluate("GDPR", time,
                controls -> new ComplianceReport());
        assertThat(run.ranAtMillis()).isEqualTo(time);
    }

    @ParameterizedTest(name = "violations {0}")
    @ValueSource(ints = {0, 1, 5})
    void parameterizedViolationCounts(int count) {
        AuditRun run = pipeline.evaluate("GDPR", 1500,
                controls -> {
                    ComplianceReport report = new ComplianceReport();
                    for (int i = 0; i < count; i++) {
                        report.add(new Violation("GDPR", "c" + i,
                                Severity.LOW, ""));
                    }
                    return report;
                });
        assertThat(run.violations()).isEqualTo(count);
    }

    @Test
    void concurrentAudits() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    pipeline.evaluate("GDPR", 1500,
                            controls -> new ComplianceReport());
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(pipeline.runCount()).isEqualTo(200);
    }

    private static ComplianceReport reportFor(
            Set<Control> controls, boolean compliant) {
        ComplianceReport report = new ComplianceReport();
        if (!compliant) {
            controls.stream()
                    .filter(control -> !control.implemented())
                    .forEach(control -> report.add(new Violation(
                            "GDPR", control.controlId(),
                            Severity.HIGH, "not implemented")));
        }
        return report;
    }
}
