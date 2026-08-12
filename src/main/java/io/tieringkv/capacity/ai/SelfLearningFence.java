package io.tieringkv.capacity.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自学习围栏（ADR-0165）：执行结果反馈 → 限幅内调整围栏参数；
 * 成功放宽 / 失败收紧 / 回滚熔断 + 审计日志。
 */
public final class SelfLearningFence {

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

    /** 调整审计记录。 */
    public record Adjustment(Params before, Params after,
                             String reason) {
    }

    private final Bounds bounds;
    private final int relaxStep;
    private final int tightenStep;
    private final int successThreshold;
    private final int failureThreshold;
    private final List<Adjustment> audit =
            new CopyOnWriteArrayList<>();
    private Params params;
    private int consecutiveSuccesses;
    private int consecutiveFailures;
    private volatile boolean circuitOpen;

    public SelfLearningFence(Params initial, Bounds bounds,
                             int relaxStep, int tightenStep,
                             int successThreshold,
                             int failureThreshold) {
        if (relaxStep < 1 || tightenStep < 1
                || successThreshold < 1 || failureThreshold < 1) {
            throw new IllegalArgumentException(
                    "steps and thresholds must be positive");
        }
        this.bounds = bounds;
        this.relaxStep = relaxStep;
        this.tightenStep = tightenStep;
        this.successThreshold = successThreshold;
        this.failureThreshold = failureThreshold;
        this.params = clamp(initial);
    }

    /** 记录成功：连续成功达到阈值后温和放宽。 */
    public synchronized void recordSuccess() {
        consecutiveSuccesses++;
        consecutiveFailures = 0;
        if (consecutiveSuccesses >= successThreshold) {
            consecutiveSuccesses = 0;
            Params before = params;
            params = clamp(new Params(
                    params.maxActionsPerDay() + relaxStep,
                    params.maxStepNodes() + relaxStep,
                    params.maxRegionsAffected() + relaxStep));
            if (!params.equals(before)) {
                audit.add(new Adjustment(before, params,
                        "relax after " + successThreshold
                                + " consecutive successes"));
            }
        }
    }

    /** 记录失败：连续失败达到阈值后收紧。 */
    public synchronized void recordFailure(String reason) {
        consecutiveFailures++;
        consecutiveSuccesses = 0;
        if (consecutiveFailures >= failureThreshold) {
            consecutiveFailures = 0;
            Params before = params;
            params = clamp(new Params(
                    params.maxActionsPerDay() - tightenStep,
                    params.maxStepNodes() - tightenStep,
                    params.maxRegionsAffected() - tightenStep));
            if (!params.equals(before)) {
                audit.add(new Adjustment(before, params,
                        "tighten: " + reason));
            }
        }
    }

    /** 记录回滚：立即熔断（拒绝后续自治执行）。 */
    public synchronized void recordRollback(String reason) {
        consecutiveFailures = 0;
        consecutiveSuccesses = 0;
        circuitOpen = true;
        audit.add(new Adjustment(params, params,
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

    public synchronized int consecutiveSuccesses() {
        return consecutiveSuccesses;
    }

    public synchronized int consecutiveFailures() {
        return consecutiveFailures;
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
