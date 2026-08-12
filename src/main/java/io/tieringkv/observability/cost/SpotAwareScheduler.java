package io.tieringkv.observability.cost;

import io.tieringkv.compliance.DataResidencyPolicy;

import java.util.List;
import java.util.Optional;

/** Spot 感知调度（ADR-0175）：期望成本（价格 × 中断惩罚）。 */
public final class SpotAwareScheduler {

    /** 候选云：价格 + 中断率 + 配额 + SLO。 */
    public record SpotOption(String cloud, double price,
                             double interruptionRate,
                             long availableQuota,
                             boolean meetsSlo) {

        public SpotOption {
            if (cloud == null || cloud.isBlank()) {
                throw new IllegalArgumentException(
                        "cloud required");
            }
            if (price < 0 || availableQuota < 0
                    || interruptionRate < 0
                    || interruptionRate > 1) {
                throw new IllegalArgumentException(
                        "price/quota/interruption invalid");
            }
        }
    }

    /** 调度任务：所需驻留 + 配额 + SLO。 */
    public record SpotTask(String taskId, String requiredResidency,
                           long requiredQuota,
                           boolean sloRequired) {

        public SpotTask {
            if (taskId == null || taskId.isBlank()) {
                throw new IllegalArgumentException(
                        "taskId required");
            }
            if (requiredResidency == null
                    || requiredResidency.isBlank()) {
                throw new IllegalArgumentException(
                        "requiredResidency required");
            }
            if (requiredQuota < 0) {
                throw new IllegalArgumentException(
                        "requiredQuota must be non-negative");
            }
        }
    }

    /** 调度决策：云 + 期望成本。 */
    public record SpotDecision(String cloud, double expectedCost) {
    }

    private final double interruptionPenalty;

    public SpotAwareScheduler() {
        this(2.0);
    }

    public SpotAwareScheduler(double interruptionPenalty) {
        if (interruptionPenalty < 0) {
            throw new IllegalArgumentException(
                    "interruption penalty must be non-negative");
        }
        this.interruptionPenalty = interruptionPenalty;
    }

    /** 期望成本 = price × (1 + 中断率 × 惩罚系数)。 */
    public double expectedCost(SpotOption option) {
        if (option == null) {
            throw new IllegalArgumentException("option required");
        }
        return option.price()
                * (1 + option.interruptionRate()
                * interruptionPenalty);
    }

    /** 选择最小期望成本且满足全部约束的云。 */
    public Optional<SpotDecision> schedule(
            SpotTask task, List<SpotOption> candidates,
            DataResidencyPolicy policy) {
        if (task == null || candidates == null || policy == null) {
            throw new IllegalArgumentException(
                    "task, candidates and policy required");
        }
        SpotOption best = null;
        double bestCost = Double.MAX_VALUE;
        for (SpotOption option : candidates) {
            if (!policy.required(option.cloud())
                    .equals(task.requiredResidency())) {
                continue;
            }
            if (option.availableQuota() < task.requiredQuota()) {
                continue;
            }
            if (task.sloRequired() && !option.meetsSlo()) {
                continue;
            }
            double cost = expectedCost(option);
            if (cost < bestCost) {
                best = option;
                bestCost = cost;
            }
        }
        return best == null ? Optional.empty()
                : Optional.of(new SpotDecision(best.cloud(),
                bestCost));
    }
}
