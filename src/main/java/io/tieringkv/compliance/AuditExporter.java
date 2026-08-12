package io.tieringkv.compliance;

import io.tieringkv.saas.TenantAuditLog;

import java.util.List;
import java.util.stream.Collectors;

/** 审计导出（ADR-0153）：JSON/CSV 审计与合规报告。 */
public final class AuditExporter {

    public String toJson(TenantAuditLog log) {
        String entries = log.all().stream()
                .map(entry -> "{"
                        + json("tenantId", entry.tenantId()) + ","
                        + json("action", entry.action()) + ","
                        + json("timestamp", String.valueOf(
                        entry.timestampMillis())) + "}")
                .collect(Collectors.joining(","));
        return "[" + entries + "]";
    }

    public String toCsv(TenantAuditLog log) {
        StringBuilder csv = new StringBuilder(
                "tenantId,action,timestampMillis\n");
        log.all().forEach(entry -> csv.append(csv(entry.tenantId()))
                .append(',').append(csv(entry.action()))
                .append(',').append(entry.timestampMillis())
                .append('\n'));
        return csv.toString();
    }

    public String toJson(ComplianceReport report) {
        String violations = report.violations().stream()
                .map(violation -> "{"
                        + json("regulation", violation.regulation())
                        + ","
                        + json("controlId", violation.controlId())
                        + ","
                        + json("severity",
                        violation.severity().name())
                        + ","
                        + json("detail", violation.detail())
                        + "}")
                .collect(Collectors.joining(","));
        return "{\"violations\":[" + violations + "],"
                + "\"count\":" + report.count() + "}";
    }

    public String toCsv(ComplianceReport report) {
        StringBuilder csv = new StringBuilder(
                "regulation,controlId,severity,detail\n");
        report.violations().forEach(violation ->
                csv.append(csv(violation.regulation()))
                        .append(',').append(csv(violation.controlId()))
                        .append(',').append(csv(
                        violation.severity().name()))
                        .append(',').append(csv(violation.detail()))
                        .append('\n'));
        return csv.toString();
    }

    private static String json(String key, String value) {
        return "\"" + escape(key) + "\":\"" + escape(value) + "\"";
    }

    private static String csv(String value) {
        String escaped = escape(value).replace(",", "\\,")
                .replace("\n", "\\n");
        return "\"" + escaped + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }
}
