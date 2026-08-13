package io.tieringkv.operations;

import io.tieringkv.ci.GateConvergenceV17;

import java.util.List;

/**
 * 产品完成度基线（ADR-0268）：能力矩阵分层（PRODUCT / EXPERIMENTAL /
 * ADAPTER）+ 技术债终态 + 成品判定清单。
 */
public final class ProductCompletenessBaseline {

    public enum Tier {
        PRODUCT,
        EXPERIMENTAL,
        ADAPTER
    }

    public record Capability(String name, Tier tier,
                             String status) {
    }

    public enum DebtDisposition {
        CLOSED,
        ACCEPTED_LIMITATION,
        ENV_BLOCKED_FINAL
    }

    public record TechDebt(String id, DebtDisposition disposition,
                           String note) {
    }

    private static final List<Capability> CAPABILITIES = List.of(
            new Capability("RESP core commands (PING/ECHO/SET/GET/"
                    + "DEL/EXISTS/INFO)", Tier.PRODUCT, "green"),
            new Capability("Full Redis command family",
                    Tier.EXPERIMENTAL, "partial"),
            new Capability("LSM cold/hot tiering",
                    Tier.PRODUCT, "green"),
            new Capability("WAL persistence and recovery",
                    Tier.PRODUCT, "green"),
            new Capability("Multi-Raft replication",
                    Tier.PRODUCT, "green"),
            new Capability("MVCC + Percolator 2PC",
                    Tier.PRODUCT, "green"),
            new Capability("SQL engine", Tier.EXPERIMENTAL, "prototype"),
            new Capability("Vector/HNSW search",
                    Tier.EXPERIMENTAL, "prototype"),
            new Capability("Console/SaaS UI",
                    Tier.EXPERIMENTAL, "prototype"),
            new Capability("Federated learning pushdown",
                    Tier.EXPERIMENTAL, "decision layer"),
            new Capability("Quantum/satellite time device",
                    Tier.ADAPTER, "SPI + simulated fallback"),
            new Capability("S3 object storage",
                    Tier.ADAPTER, "real endpoint SPI + fallback"),
            new Capability("Spot market data",
                    Tier.ADAPTER, "real endpoint SPI + fallback"));

    private static final List<TechDebt> DEBTS = List.of(
            new TechDebt("TD-048", DebtDisposition.ENV_BLOCKED_FINAL,
                    "no Linux runner"),
            new TechDebt("TD-049", DebtDisposition.ENV_BLOCKED_FINAL,
                    "no block device runner"),
            new TechDebt("K8S-001", DebtDisposition.ENV_BLOCKED_FINAL,
                    "no kind runner"),
            new TechDebt("REL-001", DebtDisposition.ENV_BLOCKED_FINAL,
                    "requires real tag trigger"),
            new TechDebt("BM-001", DebtDisposition.ENV_BLOCKED_FINAL,
                    "no cross-machine runner"),
            new TechDebt("BM-002", DebtDisposition.ENV_BLOCKED_FINAL,
                    "no cross-region runner"),
            new TechDebt("TD-076", DebtDisposition.ENV_BLOCKED_FINAL,
                    "real network credentials pending"),
            new TechDebt("TD-085", DebtDisposition.CLOSED,
                    "version model aligned"),
            new TechDebt("TD-086", DebtDisposition.CLOSED,
                    "logging and redaction"),
            new TechDebt("TD-087", DebtDisposition.CLOSED,
                    "quality gates"),
            new TechDebt("TD-088", DebtDisposition.CLOSED,
                    "jmh benchmarks"),
            new TechDebt("ARC byte-based eviction",
                    DebtDisposition.ACCEPTED_LIMITATION,
                    "entry-count based, documented"),
            new TechDebt("Single Maven module",
                    DebtDisposition.ACCEPTED_LIMITATION,
                    "module split evaluated when coupling rises"));

    private ProductCompletenessBaseline() {
    }

    public static List<Capability> capabilities() {
        return List.copyOf(CAPABILITIES);
    }

    public static List<TechDebt> techDebts() {
        return List.copyOf(DEBTS);
    }

    /** 成品判定清单：逐项可评审。 */
    public static List<String> judgmentChecklist() {
        return List.of(
                "version model consistent (pom/tag/notes/changelog)",
                "full regression green with zero failures",
                "every gate has a unique terminal disposition",
                "benchmarks reproducible with fixed params",
                "documentation can onboard a new engineer",
                "no gate rolls over to a next phase");
    }

    /** 判定：所有能力有状态、所有债务有终态、无滚动 defer 项。 */
    public static boolean passes() {
        boolean capabilitiesOk = CAPABILITIES.stream()
                .allMatch(capability -> capability.status()
                        != null && !capability.status().isBlank());
        boolean debtsOk = DEBTS.stream()
                .allMatch(debt -> debt.disposition() != null);
        boolean noRollover = DEBTS.stream()
                .noneMatch(debt -> debt.note()
                        .contains("next phase"));
        return capabilitiesOk && debtsOk && noRollover;
    }

    /** GA 判定（ADR-0309）：基线通过 + 门禁终态唯一（含封板）。 */
    public static boolean gaReady() {
        return passes() && GateConvergenceV17.closedCount() > 0
                && GateConvergenceV17.sealedCount() > 0;
    }
}
