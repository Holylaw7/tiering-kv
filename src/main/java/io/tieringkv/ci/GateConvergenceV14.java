package io.tieringkv.ci;

import java.util.List;

/**
 * 真实执行门禁收敛表 v14（ADR-0248）：每项状态 / 阻塞原因 / 预期消除阶段。
 * 可执行项全绿 + 未执行项精确登记 + 发布记录归档。
 */
public final class GateConvergenceV14 {

    public enum Status {
        GREEN_JVM,
        REGISTERED_RUNNER,
        REGISTERED_RELEASE
    }

    public record Gate(String id, String description, Status status,
                       String blocker, String expectedElimination) {
    }

    private static final List<Gate> GATES = List.of(
            new Gate("TD-048", "CI container E2E + fault injection",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 49"),
            new Gate("TD-049", "real block device disk chaos",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 49"),
            new Gate("K8S-001", "kind cluster validation",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 49"),
            new Gate("REL-001", "release.yml v1.1-v3.1 records",
                    Status.REGISTERED_RELEASE,
                    "requires real tag trigger", "Phase 49"),
            new Gate("BM-001", "cross-machine production benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-machine runner", "Phase 49"),
            new Gate("BM-002", "cross-region RTT/RTO/RPO benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 49"),
            new Gate("TD-051", "cross-region real 2PC benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 49"),
            new Gate("TD-054", "cross-region federation benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 49"),
            new Gate("TD-059", "global traffic governance benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 49"),
            new Gate("TD-060", "cross-region autonomy benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 49"),
            new Gate("TD-063", "multi-region replication benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 49"),
            new Gate("TD-066", "CI container gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 49"),
            new Gate("TD-069", "disk chaos gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 49"),
            new Gate("TD-072", "kind gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 49"),
            new Gate("TD-075", "release gate execution",
                    Status.REGISTERED_RELEASE,
                    "requires real tag trigger", "Phase 49"),
            new Gate("TD-078", "cross-machine gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires cross-machine runner", "Phase 49"),
            new Gate("TD-076", "S3/Spot real network credentials",
                    Status.GREEN_JVM,
                    "latency handshake JVM-closed; network pending",
                    "Phase 49"),
            new Gate("TD-079", "multi-organization federation",
                    Status.GREEN_JVM, "", "Phase 48"),
            new Gate("TD-080", "multi-agent reinforcement pushdown",
                    Status.GREEN_JVM, "", "Phase 48"));

    private GateConvergenceV14() {
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
}
