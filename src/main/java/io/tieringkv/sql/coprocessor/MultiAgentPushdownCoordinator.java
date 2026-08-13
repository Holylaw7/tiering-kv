package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent
        .Action;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RL 多智能体下推协调器（ADR-0250）：多智能体注册 + 加权 Q 聚合 +
 * 反馈闭环，语义层不变。
 */
public final class MultiAgentPushdownCoordinator {

    private final Map<String, ReinforcementPushdownAgent> agents =
            new ConcurrentHashMap<>();
    private final Map<String, Double> weights =
            new ConcurrentHashMap<>();
    private long federatedDecisions;

    public void registerAgent(String queryType,
                              ReinforcementPushdownAgent agent,
                              double weight) {
        if (queryType == null || queryType.isBlank()
                || agent == null || weight < 0) {
            throw new IllegalArgumentException(
                    "queryType, agent and non-negative weight "
                            + "required");
        }
        agents.put(queryType, agent);
        weights.put(queryType, weight);
    }

    /** 联邦决策：加权 Q 聚合 → 动作。 */
    public Action federatedDecide(String queryType) {
        ReinforcementPushdownAgent agent = requireAgent(queryType);
        federatedDecisions++;
        double pushdownQ = weightedQ(Action.PUSHDOWN);
        double localQ = weightedQ(Action.KEEP_LOCAL);
        return pushdownQ >= localQ
                ? Action.PUSHDOWN : Action.KEEP_LOCAL;
    }

    /** 反馈闭环：结果回传所有智能体。 */
    public void learn(String queryType, Action action,
                      double reward) {
        requireAgent(queryType);
        agents.forEach((type, agent) -> {
            double ownWeight = weights.getOrDefault(type, 0.0);
            double total = weights.values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            double share = total > 0
                    ? ownWeight / total : 0;
            agent.learn(action, reward * Math.max(share, 0.1));
        });
    }

    public double weightedQ(Action action) {
        double totalWeight = weights.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0) {
            return 0;
        }
        double sum = 0;
        for (Map.Entry<String, ReinforcementPushdownAgent> entry
                : agents.entrySet()) {
            sum += weights.getOrDefault(entry.getKey(), 0.0)
                    * entry.getValue().q(action);
        }
        return sum / totalWeight;
    }

    public long federatedDecisions() {
        return federatedDecisions;
    }

    public int agentCount() {
        return agents.size();
    }

    private ReinforcementPushdownAgent requireAgent(
            String queryType) {
        ReinforcementPushdownAgent agent = agents.get(queryType);
        if (agent == null) {
            throw new IllegalArgumentException(
                    "unknown query type " + queryType);
        }
        return agent;
    }
}
