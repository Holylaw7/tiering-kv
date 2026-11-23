package io.tieringkv.compliance;

import io.tieringkv.compliance.ComplianceReport.Severity;
import io.tieringkv.compliance.ComplianceReport.Violation;
import io.tieringkv.saas.TenantAuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计导出（ADR-0153）：JSON/CSV 格式矩阵。 */
class AuditExporterTest {

    @Test
    void auditJsonContainsEntries() {
        TenantAuditLog log = new TenantAuditLog();
        log.record("t1", "billing-roll:0");
        log.record("t1", "subscribe");
        String json = new AuditExporter().toJson(log);
        assertThat(json).startsWith("[").endsWith("]")
                .contains("\"tenantId\":\"t1\"")
                .contains("\"action\":\"subscribe\"");
    }

    @Test
    void auditCsvHeaderAndRows() {
        TenantAuditLog log = new TenantAuditLog();
        log.record("t1", "billing-roll:0");
        String csv = new AuditExporter().toCsv(log);
        assertThat(csv).startsWith("tenantId,action,timestampMillis\n")
                .contains("\"t1\",\"billing-roll:0\",");
    }

    @Test
    void emptyAuditJsonArray() {
        assertThat(new AuditExporter().toJson(
                new TenantAuditLog())).isEqualTo("[]");
    }

    @Test
    void emptyAuditCsvHeaderOnly() {
        String csv = new AuditExporter().toCsv(
                new TenantAuditLog());
        assertThat(csv).isEqualTo(
                "tenantId,action,timestampMillis\n");
    }

    @Test
    void reportJsonContainsViolations() {
        ComplianceReport report = new ComplianceReport();
        report.add(new Violation("GDPR", "g1", Severity.CRITICAL,
                "missing encryption"));
        String json = new AuditExporter().toJson(report);
        assertThat(json).startsWith("{\"violations\":[")
                .contains("\"regulation\":\"GDPR\"")
                .contains("\"severity\":\"CRITICAL\"")
                .contains("\"count\":1");
    }

    @Test
    void reportCsvHeaderAndRows() {
        ComplianceReport report = new ComplianceReport();
        report.add(new Violation("GDPR", "g1", Severity.HIGH, ""));
        String csv = new AuditExporter().toCsv(report);
        assertThat(csv).startsWith(
                "regulation,controlId,severity,detail\n")
                .contains("\"GDPR\",\"g1\",\"HIGH\"");
    }

    @Test
    void emptyReportJson() {
        String json = new AuditExporter().toJson(
                new ComplianceReport());
        assertThat(json).isEqualTo("{\"violations\":[],\"count\":0}");
    }

    @Test
    void emptyReportCsvHeaderOnly() {
        String csv = new AuditExporter().toCsv(
                new ComplianceReport());
        assertThat(csv).isEqualTo(
                "regulation,controlId,severity,detail\n");
    }

    @Test
    void jsonEscapesQuotesAndBackslashes() {
        TenantAuditLog log = new TenantAuditLog();
        log.record("t\"1", "say \\\"hi\\\"");
        String json = new AuditExporter().toJson(log);
        assertThat(json).contains("t\\\"1")
                .doesNotContain("t\"1");
    }

    @Test
    void csvEscapesCommas() {
        TenantAuditLog log = new TenantAuditLog();
        log.record("t1", "a,b");
        String csv = new AuditExporter().toCsv(log);
        assertThat(csv).contains("a\\,b");
    }

    @Test
    void multipleAuditEntriesAllExported() {
        TenantAuditLog log = new TenantAuditLog();
        for (int i = 0; i < 10; i++) {
            log.record("t" + i, "action" + i);
        }
        String json = new AuditExporter().toJson(log);
        for (int i = 0; i < 10; i++) {
            assertThat(json).contains("t" + i)
                    .contains("action" + i);
        }
    }

    @Test
    void multipleViolationsAllExported() {
        ComplianceReport report = new ComplianceReport();
        for (int i = 0; i < 5; i++) {
            report.add(new Violation("R", "c" + i,
                    Severity.MEDIUM, "d" + i));
        }
        String csv = new AuditExporter().toCsv(report);
        for (int i = 0; i < 5; i++) {
            assertThat(csv).contains(
                    "\"R\",\"c" + i + "\",\"MEDIUM\"");
        }
    }

    @ParameterizedTest(name = "entries {0}")
    @ValueSource(ints = {1, 5, 50})
    void parameterizedAuditExport(int count) {
        TenantAuditLog log = new TenantAuditLog();
        for (int i = 0; i < count; i++) {
            log.record("t" + i, "a" + i);
        }
        String csv = new AuditExporter().toCsv(log);
        assertThat(csv.lines().count()).isEqualTo(count + 1);
    }

    @ParameterizedTest(name = "violations {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedReportExport(int count) {
        ComplianceReport report = new ComplianceReport();
        for (int i = 0; i < count; i++) {
            report.add(new Violation("R", "c" + i,
                    Severity.LOW, ""));
        }
        String json = new AuditExporter().toJson(report);
        assertThat(json).contains("\"count\":" + count);
    }

    @Test
    void jsonIncludesTimestamps() {
        TenantAuditLog log = new TenantAuditLog();
        log.record("t1", "subscribe");
        assertThat(new AuditExporter().toJson(log))
                .contains("\"timestamp\":");
    }
}
