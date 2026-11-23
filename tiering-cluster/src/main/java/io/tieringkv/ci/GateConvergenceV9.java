package io.tieringkv.ci;

import java.util.List;

/**
 * 真实执行门禁收敛表 v9（ADR-0213）：每项状态 / 阻塞原因 / 预期消除阶段。
 * 可执行项全绿 + 未执行项精确登记，禁止伪报完成。
 */
public final class GateConvergenceV9 {

    /** 门禁状态。 */
    public enum Status {
        /** JVM 级可执行项已全绿（进程内口径）。 */
        GREEN_JVM,
        /** 交付物就绪，阻塞于 Linux/跨机 Runner。 */
        REGISTERED_RUNNER,
        /** 流水线就绪，阻塞于真实 tag 触发。 */
        REGISTERED_RELEASE
    }

    /** 门禁条目。 */
    public record Gate(String id, String description, Status status,
                       String blocker, String expectedElimination) {
    }

    private static final List<Gate> GATES = List.of(
            new Gate("TD-048", "CI container E2E + fault injection",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 44"),
            new Gate("TD-049", "real block device disk chaos",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 44"),
            new Gate("K8S-001", "kind cluster validation",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 44"),
            new Gate("REL-001", "release.yml v1.1-v2.6 records",
                    Status.REGISTERED_RELEASE,
                    "requires real tag trigger", "Phase 44"),
            new Gate("BM-001", "cross-machine production benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-machine runner", "Phase 44"),
            new Gate("BM-002", "cross-region RTT/RTO/RPO benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 44"),
            new Gate("TD-051", "cross-region real 2PC benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 44"),
            new Gate("TD-054", "cross-region federation benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 44"),
            new Gate("TD-059", "global traffic governance benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 44"),
            new Gate("TD-060", "cross-region autonomy benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 44"),
            new Gate("TD-063", "multi-region replication benchmark",
                    Status.REGISTERED_RUNNER,
                    "requires cross-region runner", "Phase 44"),
            new Gate("TD-066", "CI container gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 44"),
            new Gate("TD-069", "disk chaos gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 44"),
            new Gate("TD-072", "kind gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires Linux runner", "Phase 44"),
            new Gate("TD-075", "release gate execution",
                    Status.REGISTERED_RELEASE,
                    "requires real tag trigger", "Phase 44"),
            new Gate("TD-078", "cross-machine gate execution",
                    Status.REGISTERED_RUNNER,
                    "requires cross-machine runner", "Phase 44"),
            new Gate("TD-076", "S3/Spot real credentials",
                    Status.GREEN_JVM,
                    "JVM probe closed; real network pending",
                    "Phase 44"),
            new Gate("TD-079", "cross-region one-phase commit",
                    Status.GREEN_JVM, "", "Phase 43"),
            new Gate("TD-080", "multi-operator coprocessor pushdown",
                    Status.GREEN_JVM, "", "Phase 43"));

    private GateConvergenceV9() {
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
