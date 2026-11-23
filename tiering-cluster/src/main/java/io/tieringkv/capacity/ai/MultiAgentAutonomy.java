package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 多智能体自治（ADR-0186）：本地 Q + 联邦聚合 → 全局权重。 */
public final class MultiAgentAutonomy {

    /** 聚合审计记录。 */
    public record Aggregation(int round, Map<Action, Double> weights,
                              long timestampMillis) {
    }

    private final Map<String, ReinforcementAutonomy> agents =
            new ConcurrentHashMap<>();
    private final List<Aggregation> audit =
            new CopyOnWriteArrayList<>();
    private volatile int round;

    public void registerRegion(String regionId, double learningRate,
                               double epsilon, double qBound) {
        if (regionId == null || regionId.isBlank()) {
            throw new IllegalArgumentException(
                    "regionId required");
        }
        if (agents.putIfAbsent(regionId,
                new ReinforcementAutonomy(learningRate, epsilon,
                        qBound)) != null) {
            throw new IllegalArgumentException(
                    "region already registered: " + regionId);
        }
    }

    public void record(String regionId, Action action,
                       double reward) {
        requireAgent(regionId).record(action, reward);
    }

    public Action choose(String regionId) {
        return requireAgent(regionId).chooseAction();
    }

    /** 联邦聚合：各地域 Q 平均 → softmax 全局权重。 */
    public synchronized Map<Action, Double> aggregate() {
        Map<Action, Double> average = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            double sum = agents.values().stream()
                    .mapToDouble(agent -> agent.q(action)).sum();
            average.put(action, sum / agents.size());
        }
        double max = average.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0);
        Map<Action, Double> exp = new EnumMap<>(Action.class);
        double total = 0;
        for (Action action : Action.values()) {
            double value = Math.exp(average.get(action) - max);
            exp.put(action, value);
            total += value;
        }
        Map<Action, Double> weights = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            weights.put(action, exp.get(action) / total);
        }
        audit.add(new Aggregation(round++,
                Map.copyOf(weights),
                System.currentTimeMillis()));
        return Map.copyOf(weights);
    }

    public double q(String regionId, Action action) {
        return requireAgent(regionId).q(action);
    }

    public List<String> regions() {
        return List.copyOf(agents.keySet());
    }

    public int agentCount() {
        return agents.size();
    }

    public List<Aggregation> audit() {
        return List.copyOf(audit);
    }

    private ReinforcementAutonomy requireAgent(String regionId) {
        ReinforcementAutonomy agent = agents.get(regionId);
        if (agent == null) {
            throw new IllegalArgumentException(
                    "unknown region " + regionId);
        }
        return agent;
    }
}
