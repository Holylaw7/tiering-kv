package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 拓扑感知联邦自治（ADR-0193）：就近分组 → 分层聚合。 */
public final class TopologyFederatedAutonomy {

    /** 分层聚合审计记录。 */
    public record Aggregation(int round, Map<String, Long> groupSizes,
                              Map<Action, Double> weights,
                              long timestampMillis) {
    }

    private final Map<String, ReinforcementAutonomy> agents =
            new ConcurrentHashMap<>();
    private final Map<String, String> regionGroups =
            new ConcurrentHashMap<>();
    private final List<Aggregation> audit =
            new CopyOnWriteArrayList<>();
    private volatile int round;

    public void registerRegion(String regionId, String groupId,
                               double learningRate, double epsilon,
                               double qBound) {
        if (regionId == null || regionId.isBlank()
                || groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException(
                    "region and group required");
        }
        if (agents.putIfAbsent(regionId,
                new ReinforcementAutonomy(learningRate, epsilon,
                        qBound)) != null) {
            throw new IllegalArgumentException(
                    "region already registered: " + regionId);
        }
        regionGroups.put(regionId, groupId);
    }

    public void record(String regionId, Action action,
                       double reward) {
        requireAgent(regionId).record(action, reward);
    }

    /** 分层聚合：组内平均 → 组间平均 → softmax 全局权重。 */
    public synchronized Map<Action, Double> aggregate() {
        Map<String, Map<Action, Double>> groupAverages =
                aggregateGroups();
        Map<Action, Double> globalAverage =
                new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            double sum = groupAverages.values().stream()
                    .mapToDouble(group -> group.get(action)).sum();
            globalAverage.put(action,
                    sum / groupAverages.size());
        }
        Map<Action, Double> weights = softmax(globalAverage);
        Map<String, Long> groupSizes = new ConcurrentHashMap<>();
        regionGroups.values().forEach(group -> groupSizes.merge(
                group, 1L, Long::sum));
        audit.add(new Aggregation(round++, Map.copyOf(groupSizes),
                Map.copyOf(weights), System.currentTimeMillis()));
        return Map.copyOf(weights);
    }

    private Map<String, Map<Action, Double>> aggregateGroups() {
        Map<String, Map<Action, Double>> groups =
                new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry
                : regionGroups.entrySet()) {
            String group = entry.getValue();
            ReinforcementAutonomy agent = agents.get(entry.getKey());
            Map<Action, Double> groupQ = groups.computeIfAbsent(
                    group, ignored -> new EnumMap<>(Action.class));
            for (Action action : Action.values()) {
                groupQ.merge(action, agent.q(action), Double::sum);
            }
        }
        Map<String, Map<Action, Double>> averages =
                new ConcurrentHashMap<>();
        groups.forEach((group, sums) -> {
            Map<Action, Double> average =
                    new EnumMap<>(Action.class);
            long size = regionGroups.values().stream()
                    .filter(group::equals).count();
            for (Action action : Action.values()) {
                average.put(action, sums.get(action) / size);
            }
            averages.put(group, average);
        });
        return averages;
    }

    private static Map<Action, Double> softmax(
            Map<Action, Double> values) {
        double max = values.values().stream()
                .mapToDouble(Double::doubleValue).max().orElse(0);
        Map<Action, Double> exp = new EnumMap<>(Action.class);
        double total = 0;
        for (Action action : Action.values()) {
            double value = Math.exp(values.get(action) - max);
            exp.put(action, value);
            total += value;
        }
        Map<Action, Double> weights = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            weights.put(action, exp.get(action) / total);
        }
        return weights;
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
