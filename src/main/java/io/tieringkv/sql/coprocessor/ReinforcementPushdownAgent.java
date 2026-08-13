package io.tieringkv.sql.coprocessor;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * RL 动态下推智能体（ADR-0243）：状态 → 动作（下推/不下推）→ 奖励 →
 * Q 更新（epsilon-greedy），语义层不变。
 */
public final class ReinforcementPushdownAgent {

    public enum Action {
        PUSHDOWN,
        KEEP_LOCAL
    }

    private final double learningRate;
    private final double epsilon;
    private final double qBound;
    private final Random random;
    private final Map<Action, Double> q =
            new EnumMap<>(Action.class);
    private long decisions;
    private long pushdowns;

    public ReinforcementPushdownAgent(double learningRate,
                                      double epsilon,
                                      double qBound) {
        this(learningRate, epsilon, qBound, new Random());
    }

    public ReinforcementPushdownAgent(double learningRate,
                                      double epsilon,
                                      double qBound,
                                      Random random) {
        if (learningRate <= 0 || learningRate > 1
                || epsilon < 0 || epsilon > 1
                || qBound <= 0) {
            throw new IllegalArgumentException(
                    "invalid learning parameters");
        }
        this.learningRate = learningRate;
        this.epsilon = epsilon;
        this.qBound = qBound;
        this.random = random;
        q.put(Action.PUSHDOWN, 0.0);
        q.put(Action.KEEP_LOCAL, 0.0);
    }

    /** epsilon-greedy 选择动作。 */
    public Action chooseAction() {
        decisions++;
        if (random.nextDouble() < epsilon) {
            return random.nextBoolean()
                    ? Action.PUSHDOWN : Action.KEEP_LOCAL;
        }
        return q.get(Action.PUSHDOWN) >= q.get(Action.KEEP_LOCAL)
                ? Action.PUSHDOWN : Action.KEEP_LOCAL;
    }

    /** Q 更新：reward = 耗时节省（正为下推收益）。 */
    public synchronized void learn(Action action,
                                   double reward) {
        if (action == null) {
            throw new IllegalArgumentException(
                    "action required");
        }
        double current = q.get(action);
        double updated = current + learningRate
                * (reward - current);
        q.put(action, clamp(updated));
        if (action == Action.PUSHDOWN) {
            pushdowns++;
        }
    }

    /** 便捷决策：执行动作并返回选择。 */
    public Action decide() {
        Action action = chooseAction();
        return action;
    }

    public double q(Action action) {
        return q.get(action);
    }

    public long decisions() {
        return decisions;
    }

    public long pushdowns() {
        return pushdowns;
    }

    private double clamp(double value) {
        return Math.max(-qBound, Math.min(qBound, value));
    }
}
