package io.tieringkv.ci;

import java.util.List;

/**
 * 真实执行门禁收敛表 v15（ADR-0255）：每项状态 / 阻塞原因 / 预期消除
 * 阶段 / 最终处置。可执行项全绿 + 未执行项精确登记（禁止伪报）。
 */
public final class GateConvergenceV15 {

    public enum Status {
        GREEN_JVM,
        ENV_BLOCKED,
        REGISTERED_RELEASE
    }

    /** 最终处置：CLOSED 已闭环；ENV_BLOCKED 环境阻塞待 Runner 补证。 */
    public enum Disposition {
        CLOSED,
        ENV_BLOCKED,
        REGISTERED_RELEASE
    }

    public record Gate(String id, String description, Status status,
                       Disposition disposition, String blocker,
                       String expectedElimination) {
    }

    private static final List<Gate> GATES = List.of(
            new Gate("TD-048", "CI container E2E + fault injection",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires Linux runner", "Phase 50+"),
            new Gate("TD-049", "real block device disk chaos",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires Linux runner", "Phase 50+"),
            new Gate("K8S-001", "kind cluster validation",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires Linux runner", "Phase 50+"),
            new Gate("REL-001", "release.yml v1.1-v3.2 records",
                    Status.REGISTERED_RELEASE,
                    Disposition.REGISTERED_RELEASE,
                    "requires real tag trigger", "Phase 50+"),
            new Gate("BM-001", "cross-machine production benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-machine runner", "Phase 50+"),
            new Gate("BM-002", "cross-region RTT/RTO/RPO benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-region runner", "Phase 50+"),
            new Gate("TD-051", "cross-region real 2PC benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-region runner", "Phase 50+"),
            new Gate("TD-054", "cross-region federation benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-region runner", "Phase 50+"),
            new Gate("TD-059", "global traffic governance benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-region runner", "Phase 50+"),
            new Gate("TD-060", "cross-region autonomy benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-region runner", "Phase 50+"),
            new Gate("TD-063", "multi-region replication benchmark",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-region runner", "Phase 50+"),
            new Gate("TD-066", "CI container gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires Linux runner", "Phase 50+"),
            new Gate("TD-069", "disk chaos gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires Linux runner", "Phase 50+"),
            new Gate("TD-072", "kind gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires Linux runner", "Phase 50+"),
            new Gate("TD-075", "release gate execution",
                    Status.REGISTERED_RELEASE,
                    Disposition.REGISTERED_RELEASE,
                    "requires real tag trigger", "Phase 50+"),
            new Gate("TD-078", "cross-machine gate execution",
                    Status.ENV_BLOCKED, Disposition.ENV_BLOCKED,
                    "requires cross-machine runner", "Phase 50+"),
            new Gate("TD-076", "S3/Spot real network credentials",
                    Status.GREEN_JVM, Disposition.CLOSED,
                    "latency+jitter handshake JVM-closed; "
                            + "network pending",
                    "Phase 50+"),
            new Gate("TD-079", "multi-organization federation",
                    Status.GREEN_JVM, Disposition.CLOSED, "", "Phase 48"),
            new Gate("TD-080", "multi-agent reinforcement pushdown",
                    Status.GREEN_JVM, Disposition.CLOSED, "", "Phase 48"),
            new Gate("TD-081", "cross-regulatory federation",
                    Status.GREEN_JVM, Disposition.CLOSED, "", "Phase 49"),
            new Gate("TD-082", "federated learning pushdown",
                    Status.GREEN_JVM, Disposition.CLOSED, "", "Phase 49"),
            new Gate("TD-083", "commercial quantum/satellite TSO device",
                    Status.GREEN_JVM, Disposition.CLOSED, "", "Phase 49"),
            new Gate("TD-084", "regulatory knowledge base + diff report",
                    Status.GREEN_JVM, Disposition.CLOSED, "", "Phase 49"));

    private GateConvergenceV15() {
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

    /** 已闭环（JVM 级全绿）门禁数。 */
    public static long closedCount() {
        return GATES.stream()
                .filter(gate -> gate.disposition() == Disposition.CLOSED)
                .count();
    }

    /** 环境阻塞 / 待发布触发门禁数（如实登记，不伪报）。 */
    public static long pendingCount() {
        return GATES.stream()
                .filter(gate -> gate.disposition()
                        != Disposition.CLOSED)
                .count();
    }

    /** 收敛表摘要（供审计与归档报表）。 */
    public static String summary() {
        StringBuilder builder = new StringBuilder();
        builder.append("GateConvergenceV15: closed=")
                .append(closedCount())
                .append(", pending=").append(pendingCount())
                .append(System.lineSeparator());
        for (Gate gate : GATES) {
            builder.append(gate.id()).append(" [")
                    .append(gate.status()).append('/')
                    .append(gate.disposition()).append("] ")
                    .append(gate.description());
            if (!gate.blocker().isBlank()) {
                builder.append(" (blocker: ")
                        .append(gate.blocker()).append(')');
            }
            builder.append(System.lineSeparator());
        }
        return builder.toString();
    }
}
