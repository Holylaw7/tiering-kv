package io.tieringkv.ci;

import java.util.List;

/**
 * 真实执行门禁最终处置 v16（ADR-0265）：每项门禁唯一终态
 * CLOSED / ENV_BLOCKED_FINAL / REGISTERED_RELEASE，取消滚动 defer，
 * 终态理由 + 封板阶段可审计。
 */
public final class GateConvergenceV16 {

    public enum Status {
        GREEN_JVM,
        ENV_BLOCKED,
        REGISTERED_RELEASE
    }

    public enum Disposition {
        CLOSED,
        ENV_BLOCKED_FINAL,
        REGISTERED_RELEASE
    }

    public record Gate(String id, String description, Status status,
                       Disposition disposition, String finalReason,
                       String sealedPhase) {
    }

    private static final List<Gate> GATES = List.of(
            new Gate("TD-048", "CI container E2E + fault injection",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "deliverables ready; no Linux runner available",
                    "Phase 50"),
            new Gate("TD-049", "real block device disk chaos",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "deliverables ready; requires Linux block device",
                    "Phase 50"),
            new Gate("K8S-001", "kind cluster validation",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "deliverables ready; requires Linux runner",
                    "Phase 50"),
            new Gate("REL-001", "release.yml v1.0-v3.2 records",
                    Status.REGISTERED_RELEASE,
                    Disposition.REGISTERED_RELEASE,
                    "pipeline ready; requires real tag trigger",
                    "Phase 50"),
            new Gate("BM-001", "cross-machine production benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-machine runner", "Phase 50"),
            new Gate("BM-002", "cross-region RTT/RTO/RPO benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-region runner", "Phase 50"),
            new Gate("TD-051", "cross-region real 2PC benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-region runner", "Phase 50"),
            new Gate("TD-054", "cross-region federation benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-region runner", "Phase 50"),
            new Gate("TD-059", "global traffic governance benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-region runner", "Phase 50"),
            new Gate("TD-060", "cross-region autonomy benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-region runner", "Phase 50"),
            new Gate("TD-063", "multi-region replication benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-region runner", "Phase 50"),
            new Gate("TD-066", "CI container gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires Linux runner", "Phase 50"),
            new Gate("TD-069", "disk chaos gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires Linux runner", "Phase 50"),
            new Gate("TD-072", "kind gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires Linux runner", "Phase 50"),
            new Gate("TD-075", "release gate execution",
                    Status.REGISTERED_RELEASE,
                    Disposition.REGISTERED_RELEASE,
                    "pipeline ready; requires real tag trigger",
                    "Phase 50"),
            new Gate("TD-078", "cross-machine gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "requires cross-machine runner", "Phase 50"),
            new Gate("TD-076", "S3/Spot real network credentials",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED_FINAL,
                    "JVM handshake closed; real network requires "
                            + "runner and credentials", "Phase 50"),
            new Gate("TD-079", "multi-organization federation",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "JVM matrix green", "Phase 48"),
            new Gate("TD-080", "multi-agent reinforcement pushdown",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "JVM matrix green", "Phase 48"),
            new Gate("TD-081", "cross-regulatory federation",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "JVM matrix green", "Phase 49"),
            new Gate("TD-082", "federated learning pushdown",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "JVM matrix green", "Phase 49"),
            new Gate("TD-083", "commercial quantum/satellite TSO device",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "JVM matrix green (adapter)", "Phase 49"),
            new Gate("TD-084", "regulatory knowledge base + diff report",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "JVM matrix green", "Phase 49"),
            new Gate("TD-085", "version model and artifact alignment",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "consistency matrix green", "Phase 50"),
            new Gate("TD-086", "structured logging and redaction",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "redaction matrix green", "Phase 50"),
            new Gate("TD-087", "quality gates",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "gate configuration verified", "Phase 50"),
            new Gate("TD-088", "jmh benchmark engineering",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "benchmark skeleton verified", "Phase 50"));

    private GateConvergenceV16() {
    }

    public static List<Gate> gates() {
        return List.copyOf(GATES);
    }

    public static Gate gate(String id) {
        return GATES.stream()
                .filter(gate -> gate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown gate " + id));
    }

    public static long closedCount() {
        return GATES.stream()
                .filter(gate -> gate.disposition()
                        == Disposition.CLOSED)
                .count();
    }

    public static long finalBlockedCount() {
        return GATES.stream()
                .filter(gate -> gate.disposition()
                        == Disposition.ENV_BLOCKED_FINAL)
                .count();
    }

    public static long registeredReleaseCount() {
        return GATES.stream()
                .filter(gate -> gate.disposition()
                        == Disposition.REGISTERED_RELEASE)
                .count();
    }

    public static String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("GateConvergenceV16: closed=")
                .append(closedCount())
                .append(", envBlockedFinal=")
                .append(finalBlockedCount())
                .append(", registeredRelease=")
                .append(registeredReleaseCount())
                .append(System.lineSeparator());
        for (Gate gate : GATES) {
            builder.append(gate.id()).append(" [")
                    .append(gate.disposition()).append("] ")
                    .append(gate.description())
                    .append(" -> ").append(gate.finalReason())
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }
}
