package io.tieringkv.capacity.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多目标自学习围栏（ADR-0172）：成本 × 风险 × SLO 加权评分 →
 * 限幅内调整围栏参数 + 审计。
 */
public final class MultiObjectiveFence {

    /** 围栏参数。 */
    public record Params(int maxActionsPerDay, int maxStepNodes,
                         int maxRegionsAffected) {
    }

    /** 参数安全上下界。 */
    public record Bounds(int minActionsPerDay, int maxActionsPerDay,
                         int minStepNodes, int maxStepNodes,
                         int minRegions, int maxRegions) {

        public Bounds {
            if (minActionsPerDay < 1 || minStepNodes < 1
                    || minRegions < 1
                    || maxActionsPerDay < minActionsPerDay
                    || maxStepNodes < minStepNodes
                    || maxRegions < minRegions) {
                throw new IllegalArgumentException(
                        "invalid bounds");
            }
        }
    }

    /** 多目标权重：成本 / 风险 / SLO（非负）。 */
    public record Weights(double costWeight, double riskWeight,
                          double sloWeight) {

        public Weights {
            if (costWeight < 0 || riskWeight < 0 || sloWeight < 0
                    || costWeight + riskWeight + sloWeight == 0) {
                throw new IllegalArgumentException(
                        "weights must be non-negative and non-zero");
            }
        }
    }

    /** 多指标反馈：成本节约 / 失败率 / SLO 达成（0~1）。 */
    public record Feedback(double costSaving, double failureRate,
                           double sloAttainment) {

        public Feedback {
            if (costSaving < 0 || costSaving > 1
                    || failureRate < 0 || failureRate > 1
                    || sloAttainment < 0 || sloAttainment > 1) {
                throw new IllegalArgumentException(
                        "feedback must be in [0,1]");
            }
        }
    }

    /** 调整审计记录。 */
    public record Adjustment(Params before, Params after,
                             double score, String reason) {
    }

    private final Bounds bounds;
    private final Weights weights;
    private final double relaxThreshold;
    private final double tightenThreshold;
    private final int relaxStep;
    private final int tightenStep;
    private final List<Adjustment> audit =
            new CopyOnWriteArrayList<>();
    private Params params;
    private volatile boolean circuitOpen;

    public MultiObjectiveFence(Params initial, Bounds bounds,
                               Weights weights,
                               double relaxThreshold,
                               double tightenThreshold,
                               int relaxStep, int tightenStep) {
        if (relaxThreshold <= tightenThreshold) {
            throw new IllegalArgumentException(
                    "relax threshold must exceed tighten threshold");
        }
        if (relaxStep < 1 || tightenStep < 1) {
            throw new IllegalArgumentException(
                    "steps must be positive");
        }
        this.bounds = bounds;
        this.weights = weights;
        this.relaxThreshold = relaxThreshold;
        this.tightenThreshold = tightenThreshold;
        this.relaxStep = relaxStep;
        this.tightenStep = tightenStep;
        this.params = clamp(initial);
    }

    /** 加权评分：成本节约 + (1-失败率) + SLO 达成。 */
    public double score(Feedback feedback) {
        if (feedback == null) {
            throw new IllegalArgumentException(
                    "feedback required");
        }
        double total = weights.costWeight() + weights.riskWeight()
                + weights.sloWeight();
        return (weights.costWeight() * feedback.costSaving()
                + weights.riskWeight() * (1 - feedback.failureRate())
                + weights.sloWeight() * feedback.sloAttainment())
                / total;
    }

    /** 记录反馈：高分放宽 / 低分收紧 / 中间保持。 */
    public synchronized Adjustment record(Feedback feedback) {
        if (feedback == null) {
            throw new IllegalArgumentException(
                    "feedback required");
        }
        double score = score(feedback);
        Params before = params;
        if (score >= relaxThreshold) {
            params = clamp(new Params(
                    params.maxActionsPerDay() + relaxStep,
                    params.maxStepNodes() + relaxStep,
                    params.maxRegionsAffected() + relaxStep));
        } else if (score <= tightenThreshold) {
            params = clamp(new Params(
                    params.maxActionsPerDay() - tightenStep,
                    params.maxStepNodes() - tightenStep,
                    params.maxRegionsAffected() - tightenStep));
        }
        if (!params.equals(before)) {
            audit.add(new Adjustment(before, params, score,
                    score >= relaxThreshold ? "relax"
                            : "tighten"));
        }
        return new Adjustment(before, params, score,
                score >= relaxThreshold ? "relax"
                        : score <= tightenThreshold ? "tighten"
                        : "maintain");
    }

    /** 回滚：立即熔断。 */
    public synchronized void recordRollback(String reason) {
        circuitOpen = true;
        audit.add(new Adjustment(params, params, 0,
                "circuit open: " + reason));
    }

    public synchronized void resetCircuit() {
        circuitOpen = false;
    }

    public boolean circuitOpen() {
        return circuitOpen;
    }

    public synchronized Params params() {
        return params;
    }

    public List<Adjustment> audit() {
        return List.copyOf(audit);
    }

    private Params clamp(Params candidate) {
        return new Params(
                Math.min(bounds.maxActionsPerDay(),
                        Math.max(bounds.minActionsPerDay(),
                                candidate.maxActionsPerDay())),
                Math.min(bounds.maxStepNodes(),
                        Math.max(bounds.minStepNodes(),
                                candidate.maxStepNodes())),
                Math.min(bounds.maxRegions(),
                        Math.max(bounds.minRegions(),
                                candidate.maxRegionsAffected())));
    }
}
