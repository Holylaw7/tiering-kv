package io.tieringkv.ci;

import java.util.List;

/** GA 门禁终态 v17（ADR-0305）：CLOSED / SEALED_GA / REGISTERED_RELEASE。 */
public final class GateConvergenceV17 {

    public enum Disposition {
        CLOSED,
        SEALED_GA,
        REGISTERED_RELEASE
    }

    public record Gate(String id, String description,
                       Disposition disposition,
                       String finalReason) {
    }

    private static final List<Gate> GATES = List.of(
            new Gate("TD-048", "CI container E2E + fault injection",
                    Disposition.SEALED_GA,
                    "deliverables ready; no remote Linux runner"),
            new Gate("TD-049", "real block device disk chaos",
                    Disposition.SEALED_GA,
                    "deliverables ready; no block device runner"),
            new Gate("K8S-001", "kind cluster validation",
                    Disposition.SEALED_GA,
                    "deliverables ready; no kind runner"),
            new Gate("REL-001", "release pipeline records",
                    Disposition.REGISTERED_RELEASE,
                    "pipeline ready; no remote tag trigger"),
            new Gate("BM-001", "cross-machine benchmark",
                    Disposition.SEALED_GA,
                    "no cross-machine runner"),
            new Gate("BM-002", "cross-region benchmark",
                    Disposition.SEALED_GA,
                    "no cross-region runner"),
            new Gate("TD-076", "real network credentials",
                    Disposition.SEALED_GA,
                    "JVM probe closed; network pending"),
            new Gate("TD-079", "multi-organization federation",
                    Disposition.CLOSED, "JVM matrix green"),
            new Gate("TD-081", "cross-regulatory federation",
                    Disposition.CLOSED, "JVM matrix green"),
            new Gate("TD-085", "version model alignment",
                    Disposition.CLOSED, "consistency matrix green"),
            new Gate("TD-086", "structured logging",
                    Disposition.CLOSED, "redaction matrix green"),
            new Gate("TD-087", "quality gates",
                    Disposition.CLOSED, "gate configuration green"),
            new Gate("TD-088", "jmh benchmark engineering",
                    Disposition.CLOSED, "benchmark skeleton green"),
            new Gate("TD-089", "linearizability verification",
                    Disposition.CLOSED, "history matrix green"),
            new Gate("TD-090", "consumer groups",
                    Disposition.CLOSED, "group matrix green"));

    private GateConvergenceV17() {
    }

    public static List<Gate> gates() {
        return List.copyOf(GATES);
    }

    public static long sealedCount() {
        return GATES.stream().filter(gate -> gate.disposition()
                == Disposition.SEALED_GA).count();
    }

    public static long closedCount() {
        return GATES.stream().filter(gate -> gate.disposition()
                == Disposition.CLOSED).count();
    }

    public static String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("GateConvergenceV17: sealed=")
                .append(sealedCount()).append(", closed=")
                .append(closedCount()).append(System.lineSeparator());
        for (Gate gate : GATES) {
            builder.append(gate.id()).append(" [")
                    .append(gate.disposition()).append("] ")
                    .append(gate.finalReason())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }
}
