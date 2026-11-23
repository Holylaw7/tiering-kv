package io.tieringkv.observability.cost;

import io.tieringkv.compliance.DataResidencyPolicy;

import java.util.List;
import java.util.Optional;

/** 多云成本竞价调度（ADR-0168）：最低成本 + 主权/配额/SLO 约束。 */
public final class CloudCostScheduler {

    /** 候选云：价格 + 可用配额 + SLO 达标。 */
    public record CloudOption(String cloud, double pricePerUnit,
                              long availableQuota,
                              boolean meetsSlo) {

        public CloudOption {
            if (cloud == null || cloud.isBlank()) {
                throw new IllegalArgumentException(
                        "cloud required");
            }
            if (pricePerUnit < 0 || availableQuota < 0) {
                throw new IllegalArgumentException(
                        "price and quota must be non-negative");
            }
        }
    }

    /** 调度任务：所需驻留 + 配额 + SLO 要求。 */
    public record ScheduleTask(String taskId, String requiredResidency,
                               long requiredQuota,
                               boolean sloRequired) {

        public ScheduleTask {
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

    /** 调度决策：候选云 + 单位价格。 */
    public record SchedulingDecision(String cloud,
                                     double pricePerUnit) {
    }

    /** 选择最低成本且满足全部约束的云。 */
    public Optional<SchedulingDecision> schedule(
            ScheduleTask task, List<CloudOption> candidates,
            DataResidencyPolicy policy) {
        if (task == null || candidates == null || policy == null) {
            throw new IllegalArgumentException(
                    "task, candidates and policy required");
        }
        CloudOption best = null;
        for (CloudOption option : candidates) {
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
            if (best == null
                    || option.pricePerUnit() < best.pricePerUnit()) {
                best = option;
            }
        }
        return best == null ? Optional.empty()
                : Optional.of(new SchedulingDecision(
                best.cloud(), best.pricePerUnit()));
    }
}
