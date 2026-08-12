package io.tieringkv.observability.cost;

import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotDecision;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;

import java.util.List;
import java.util.Optional;

/** Spot 中断迁移（ADR-0183）：中断 → 备用云选择 → 迁移计划。 */
public final class SpotMigrationPlanner {

    /** 迁移计划：任务 + 源/目标云 + 期望成本。 */
    public record MigrationPlan(String taskId, String fromCloud,
                                String toCloud,
                                double expectedCost) {
    }

    private final SpotAwareScheduler scheduler;

    public SpotMigrationPlanner() {
        this(2.0);
    }

    public SpotMigrationPlanner(double interruptionPenalty) {
        this.scheduler = new SpotAwareScheduler(
                interruptionPenalty);
    }

    /** 规划迁移：排除中断云，按期望成本选备用云。 */
    public Optional<MigrationPlan> plan(
            String taskId, String interruptedCloud,
            List<SpotOption> candidates, SpotTask requirements,
            DataResidencyPolicy policy) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException(
                    "taskId required");
        }
        if (interruptedCloud == null || interruptedCloud.isBlank()) {
            throw new IllegalArgumentException(
                    "interruptedCloud required");
        }
        if (candidates == null || requirements == null
                || policy == null) {
            throw new IllegalArgumentException(
                    "candidates, requirements and policy required");
        }
        List<SpotOption> backup = candidates.stream()
                .filter(option -> !option.cloud()
                        .equals(interruptedCloud))
                .toList();
        Optional<SpotDecision> decision = scheduler.schedule(
                requirements, backup, policy);
        return decision.map(value -> new MigrationPlan(taskId,
                interruptedCloud, value.cloud(),
                value.expectedCost()));
    }
}
