package io.tieringkv.compliance;

import io.tieringkv.compliance.RegulationVersion.Version;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/** 持续审计流水线（ADR-0159）：周期评估 → 报告 → 导出 → 记录。 */
public final class ContinuousAuditPipeline {

    /** 审计运行记录。 */
    public record AuditRun(String regulation, String versionId,
                           long ranAtMillis, int violations,
                           String exportJson) {
    }

    private final RegulationVersionStore store;
    private final AuditExporter exporter;
    private final List<AuditRun> runs =
            new CopyOnWriteArrayList<>();

    public ContinuousAuditPipeline(RegulationVersionStore store,
                                   AuditExporter exporter) {
        this.store = store;
        this.exporter = exporter;
    }

    /** 评估：取生效版本 → 控制项评估 → 导出 → 记录。 */
    public AuditRun evaluate(String regulation, long nowMillis,
                             Function<java.util.Set<
                                     RegulationMapper.Control>,
                                     ComplianceReport> evaluator) {
        if (regulation == null || regulation.isBlank()) {
            throw new IllegalArgumentException(
                    "regulation required");
        }
        if (evaluator == null) {
            throw new IllegalArgumentException(
                    "evaluator required");
        }
        Version version = store.effective(regulation, nowMillis)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no effective version for "
                                + regulation));
        ComplianceReport report = evaluator.apply(
                version.controls());
        AuditRun run = new AuditRun(regulation,
                version.versionId(), nowMillis, report.count(),
                exporter.toJson(report));
        runs.add(run);
        return run;
    }

    public List<AuditRun> runs() {
        return List.copyOf(runs);
    }

    public List<AuditRun> runsFor(String regulation) {
        return runs.stream()
                .filter(run -> run.regulation().equals(regulation))
                .toList();
    }

    public int runCount() {
        return runs.size();
    }
}
