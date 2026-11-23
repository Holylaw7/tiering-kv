package io.tieringkv.capacity.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** 强化学习自治（ADR-0180）：简化 Q 学习权重进化。 */
public final class ReinforcementAutonomy {

    public enum Action {
        RELAX,
        TIGHTEN,
        MAINTAIN
    }

    private final double learningRate;
    private final double epsilon;
    private final double qBound;
    private final Random random;
    private final Map<Action, Double> q =
            new ConcurrentHashMap<>(new EnumMap<>(Action.class));

    public ReinforcementAutonomy(double learningRate,
                                 double epsilon, double qBound) {
        this(learningRate, epsilon, qBound, new Random());
    }

    public ReinforcementAutonomy(double learningRate,
                                 double epsilon, double qBound,
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
        for (Action action : Action.values()) {
            q.put(action, 0.0);
        }
    }

    /** epsilon-greedy 选择动作。 */
    public Action chooseAction() {
        if (random.nextDouble() < epsilon) {
            Action[] actions = Action.values();
            return actions[random.nextInt(actions.length)];
        }
        Action best = Action.MAINTAIN;
        double bestQ = Double.NEGATIVE_INFINITY;
        for (Action action : Action.values()) {
            double value = q.get(action);
            if (value > bestQ) {
                bestQ = value;
                best = action;
            }
        }
        return best;
    }

    /** 更新所选动作的 Q 值。 */
    public synchronized void record(Action action, double reward) {
        if (action == null) {
            throw new IllegalArgumentException(
                    "action required");
        }
        double current = q.get(action);
        double updated = current + learningRate
                * (reward - current);
        q.put(action, clamp(updated));
    }

    /** softmax 权重：总和为 1。 */
    public synchronized Map<Action, Double> weights() {
        double max = q.values().stream()
                .mapToDouble(Double::doubleValue).max()
                .orElse(0);
        Map<Action, Double> exp = new EnumMap<>(Action.class);
        double sum = 0;
        for (Action action : Action.values()) {
            double value = Math.exp(q.get(action) - max);
            exp.put(action, value);
            sum += value;
        }
        Map<Action, Double> weights = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            weights.put(action, exp.get(action) / sum);
        }
        return Map.copyOf(weights);
    }

    public double q(Action action) {
        return q.get(action);
    }

    private double clamp(double value) {
        return Math.max(-qBound, Math.min(qBound, value));
    }
}
