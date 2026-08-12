package io.tieringkv.compliance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 合规报告（ADR-0153）：违规项 + 严重级。 */
public final class ComplianceReport {

    public enum Severity {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /** 违规项：法规 + 控制项 + 严重级 + 详情。 */
    public record Violation(String regulation, String controlId,
                            Severity severity, String detail) {

        public Violation {
            if (regulation == null || regulation.isBlank()) {
                throw new IllegalArgumentException(
                        "regulation required");
            }
            if (controlId == null || controlId.isBlank()) {
                throw new IllegalArgumentException(
                        "controlId required");
            }
            if (severity == null) {
                throw new IllegalArgumentException(
                        "severity required");
            }
        }
    }

    private final List<Violation> violations =
            new CopyOnWriteArrayList<>();

    public void add(Violation violation) {
        if (violation == null) {
            throw new IllegalArgumentException(
                    "violation required");
        }
        violations.add(violation);
    }

    public List<Violation> violations() {
        return List.copyOf(violations);
    }

    public List<Violation> bySeverity(Severity severity) {
        return violations.stream()
                .filter(violation -> violation.severity() == severity)
                .toList();
    }

    public int count() {
        return violations.size();
    }

    public boolean hasCritical() {
        return violations.stream().anyMatch(
                violation -> violation.severity() == Severity.CRITICAL);
    }
}
