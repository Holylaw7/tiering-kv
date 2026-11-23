package io.tieringkv.capacity.ai;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 容量自治控制器（ADR-0151）：护栏内执行扩容建议；
 * 单步上限 / 日上限 / 高水位拒绝，失败登记。
 */
public final class AutonomousCapacityController {

    public enum Outcome {
        EXECUTED,
        SKIPPED,
        REJECTED
    }

    /** 调整记录：结果 + 当前/目标节点 + 原因。 */
    public record Adjustment(Outcome outcome, int currentNodes,
                             int targetNodes, String reason) {
    }

    private final int maxStepNodes;
    private final int maxDailyAdjustments;
    private final int highWatermarkNodes;
    private final List<String> rejectedReasons =
            new CopyOnWriteArrayList<>();
    private int currentNodes;
    private int adjustmentsToday;

    public AutonomousCapacityController(int initialNodes,
                                        int maxStepNodes,
                                        int maxDailyAdjustments,
                                        int highWatermarkNodes) {
        if (initialNodes < 1 || maxStepNodes < 1
                || maxDailyAdjustments < 1
                || highWatermarkNodes < 1) {
            throw new IllegalArgumentException(
                    "limits must be positive");
        }
        this.currentNodes = initialNodes;
        this.maxStepNodes = maxStepNodes;
        this.maxDailyAdjustments = maxDailyAdjustments;
        this.highWatermarkNodes = highWatermarkNodes;
    }

    /** 执行建议：护栏校验通过才扩容，幂等（无变化跳过）。 */
    public synchronized Adjustment apply(
            AutoCapacityAdvisor.Advice advice) {
        if (advice == null) {
            throw new IllegalArgumentException("advice required");
        }
        int target = Math.max(1, advice.nodes());
        if (target == currentNodes) {
            return new Adjustment(Outcome.SKIPPED, currentNodes,
                    target, "no change");
        }
        int delta = target - currentNodes;
        if (delta > maxStepNodes) {
            return reject(target, "step exceeds limit "
                    + maxStepNodes);
        }
        if (target > highWatermarkNodes) {
            return reject(target, "high watermark reached");
        }
        if (adjustmentsToday >= maxDailyAdjustments) {
            return reject(target, "daily adjustment limit reached");
        }
        currentNodes = target;
        adjustmentsToday++;
        return new Adjustment(Outcome.EXECUTED, currentNodes, target,
                "");
    }

    /** 日切：重置当日调整计数。 */
    public synchronized void newDay() {
        adjustmentsToday = 0;
    }

    /** 回滚恢复：直接设置节点数，不消耗当日预算。 */
    public synchronized void restore(int nodes) {
        if (nodes < 1) {
            throw new IllegalArgumentException(
                    "nodes must be positive");
        }
        currentNodes = nodes;
    }

    public synchronized int currentNodes() {
        return currentNodes;
    }

    public synchronized int adjustmentsToday() {
        return adjustmentsToday;
    }

    public List<String> rejectedReasons() {
        return List.copyOf(rejectedReasons);
    }

    private Adjustment reject(int target, String reason) {
        rejectedReasons.add(reason);
        return new Adjustment(Outcome.REJECTED, currentNodes, target,
                reason);
    }
}
