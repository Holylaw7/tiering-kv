package io.tieringkv.compliance;

import io.tieringkv.compliance.ComplianceReport.Severity;
import io.tieringkv.compliance.ComplianceReport.Violation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 合规报告（ADR-0153）：违规项 + 严重级过滤。 */
class ComplianceReportTest {

    @Test
    void addAndCount() {
        ComplianceReport report = new ComplianceReport();
        report.add(violation("GDPR", "g1", Severity.HIGH));
        report.add(violation("GDPR", "g2", Severity.CRITICAL));
        assertThat(report.count()).isEqualTo(2);
        assertThat(report.violations()).hasSize(2);
    }

    @Test
    void filterBySeverity() {
        ComplianceReport report = new ComplianceReport();
        report.add(violation("GDPR", "g1", Severity.LOW));
        report.add(violation("GDPR", "g2", Severity.HIGH));
        report.add(violation("GDPR", "g3", Severity.HIGH));
        assertThat(report.bySeverity(Severity.HIGH)).hasSize(2);
        assertThat(report.bySeverity(Severity.LOW)).hasSize(1);
        assertThat(report.bySeverity(Severity.CRITICAL)).isEmpty();
    }

    @Test
    void hasCriticalTrue() {
        ComplianceReport report = new ComplianceReport();
        report.add(violation("GDPR", "g1", Severity.CRITICAL));
        assertThat(report.hasCritical()).isTrue();
    }

    @Test
    void hasCriticalFalseWithoutCritical() {
        ComplianceReport report = new ComplianceReport();
        report.add(violation("GDPR", "g1", Severity.HIGH));
        assertThat(report.hasCritical()).isFalse();
    }

    @Test
    void emptyReportNoCritical() {
        assertThat(new ComplianceReport().hasCritical()).isFalse();
        assertThat(new ComplianceReport().count()).isZero();
    }

    @Test
    void violationsAreCopied() {
        ComplianceReport report = new ComplianceReport();
        Violation violation = violation("GDPR", "g1", Severity.MEDIUM);
        report.add(violation);
        java.util.List<Violation> view = report.violations();
        report.add(violation("GDPR", "g2", Severity.MEDIUM));
        assertThat(view).hasSize(1);
        assertThat(report.count()).isEqualTo(2);
    }

    @Test
    void nullViolationRejected() {
        assertThatThrownBy(() -> new ComplianceReport().add(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRegulationRejected() {
        assertThatThrownBy(() -> new Violation("", "c",
                Severity.LOW, "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankControlRejected() {
        assertThatThrownBy(() -> new Violation("R", "",
                Severity.LOW, "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSeverityRejected() {
        assertThatThrownBy(() -> new Violation("R", "c", null, "d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "severity {0}")
    @EnumSource(Severity.class)
    void parameterizedSeverities(Severity severity) {
        ComplianceReport report = new ComplianceReport();
        report.add(violation("R", "c", severity));
        assertThat(report.bySeverity(severity)).hasSize(1);
    }

    @Test
    void detailCarried() {
        ComplianceReport report = new ComplianceReport();
        Violation violation = new Violation("GDPR", "g1",
                Severity.HIGH, "encryption missing");
        report.add(violation);
        assertThat(report.violations().get(0).detail())
                .isEqualTo("encryption missing");
    }

    @Test
    void multipleReportsIndependent() {
        ComplianceReport first = new ComplianceReport();
        ComplianceReport second = new ComplianceReport();
        first.add(violation("GDPR", "g1", Severity.HIGH));
        assertThat(second.count()).isZero();
    }

    @Test
    void concurrentAdds() throws Exception {
        ComplianceReport report = new ComplianceReport();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    report.add(violation("R", "c" + i,
                            Severity.MEDIUM));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(report.count()).isEqualTo(800);
    }

    @Test
    void violationsSortedBySeverityFilter() {
        ComplianceReport report = new ComplianceReport();
        report.add(violation("R", "a", Severity.CRITICAL));
        report.add(violation("R", "b", Severity.LOW));
        assertThat(report.bySeverity(Severity.CRITICAL))
                .extracting(Violation::controlId)
                .containsExactly("a");
    }

    private static Violation violation(String regulation,
                                       String controlId,
                                       Severity severity) {
        return new Violation(regulation, controlId, severity, "");
    }
}
