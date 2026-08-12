package io.tieringkv.operations.slo;

/** SLO 预算容量规划（ADR-0170）：达成率 → 扩容建议。 */
public final class SloBudgetPlanner {

    public enum Action {
        SCALE_UP,
        MAINTAIN
    }

    /** 预算计划：当前/建议节点 + 动作。 */
    public record BudgetPlan(double compliance, double target,
                             int currentNodes, int suggestedNodes,
                             Action action) {
    }

    private final double headroomFactor;

    public SloBudgetPlanner() {
        this(2.0);
    }

    public SloBudgetPlanner(double headroomFactor) {
        if (headroomFactor < 1) {
            throw new IllegalArgumentException(
                    "headroom factor must be >= 1");
        }
        this.headroomFactor = headroomFactor;
    }

    /** 按 SLO 达成率与目标计算容量建议。 */
    public BudgetPlan plan(double compliance, double target,
                           int currentNodes, int maxNodes) {
        if (compliance < 0 || compliance > 1) {
            throw new IllegalArgumentException(
                    "compliance must be in [0,1]");
        }
        if (target <= 0 || target > 1) {
            throw new IllegalArgumentException(
                    "target must be in (0,1]");
        }
        if (currentNodes < 1) {
            throw new IllegalArgumentException(
                    "currentNodes must be positive");
        }
        if (maxNodes < currentNodes) {
            throw new IllegalArgumentException(
                    "maxNodes must be >= currentNodes");
        }
        if (compliance >= target) {
            return new BudgetPlan(compliance, target, currentNodes,
                    currentNodes, Action.MAINTAIN);
        }
        double deficit = (target - compliance) / target;
        int increase = (int) Math.ceil(
                currentNodes * deficit * headroomFactor);
        int suggested = Math.min(maxNodes,
                currentNodes + Math.max(1, increase));
        return new BudgetPlan(compliance, target, currentNodes,
                suggested, Action.SCALE_UP);
    }
}
