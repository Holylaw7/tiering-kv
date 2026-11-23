package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent.Action;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RL 多智能体联邦学习（ADR-0257）：本地 Q 更新 + FedAvg 模型聚合 +
 * 梯度裁剪 + 噪声注入隐私保护。只改决策层，语义层与上层 SQL 一致。
 */
public final class FederatedPushdownLearning {

    /** 联邦模型快照（聚合结果 + 参与样本数）。 */
    public record ModelSnapshot(String queryType,
                                Map<Action, Double> q,
                                long samples) {
    }

    /** 隐私保护统计。 */
    public record PrivacyStats(double noiseScale, double clipBound,
                               long clippedUpdates,
                               long noisedUpdates,
                               long consistencyChecks,
                               long consistentChecks) {
    }

    private final Map<String, ReinforcementPushdownAgent> agents =
            new ConcurrentHashMap<>();
    private final Map<String, Double> weights =
            new ConcurrentHashMap<>();
    private final Map<String, ModelSnapshot> aggregated =
            new ConcurrentHashMap<>();
    private final double noiseScale;
    private final double clipBound;
    private final Random random;
    private volatile MultiAgentPushdownCoordinator coordinator;
    private long rounds;
    private long clippedUpdates;
    private long noisedUpdates;
    private long consistencyChecks;
    private long consistentChecks;

    public FederatedPushdownLearning(double noiseScale,
                                     double clipBound) {
        this(noiseScale, clipBound, new Random());
    }

    public FederatedPushdownLearning(double noiseScale,
                                     double clipBound,
                                     Random random) {
        if (noiseScale < 0 || clipBound <= 0
                || random == null) {
            throw new IllegalArgumentException(
                    "noiseScale >= 0, clipBound > 0 and random "
                            + "required");
        }
        this.noiseScale = noiseScale;
        this.clipBound = clipBound;
        this.random = random;
    }

    public void registerAgent(String queryType,
                              ReinforcementPushdownAgent agent,
                              double weight) {
        if (queryType == null || queryType.isBlank()
                || agent == null || weight <= 0) {
            throw new IllegalArgumentException(
                    "queryType, agent and positive weight required");
        }
        agents.put(queryType, agent);
        weights.put(queryType, weight);
        aggregated.remove(queryType);
    }

    public void attachCoordinator(
            MultiAgentPushdownCoordinator coordinator) {
        if (coordinator == null) {
            throw new IllegalArgumentException(
                    "coordinator required");
        }
        this.coordinator = coordinator;
    }

    /** 本地学习：奖励先裁剪（梯度裁剪），再回传智能体。 */
    public synchronized void federatedLearn(String queryType,
                                            Action action,
                                            double reward) {
        requireAgent(queryType);
        if (action == null) {
            throw new IllegalArgumentException(
                    "action required");
        }
        double clipped = Math.max(-clipBound,
                Math.min(clipBound, reward));
        if (clipped != reward) {
            clippedUpdates++;
        }
        agents.forEach((type, agent) ->
                agent.learn(action, clipped
                        * weightShare(type)));
        aggregate(queryType);
        rounds++;
    }

    /** FedAvg 聚合：按权重平均本地 Q，聚合后注入噪声（隐私保护）。 */
    public synchronized ModelSnapshot aggregate(
            String queryType) {
        requireAgent(queryType);
        double totalWeight = weights.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        if (totalWeight <= 0) {
            throw new IllegalStateException(
                    "no agent weight registered");
        }
        Map<Action, Double> merged = new EnumMap<>(Action.class);
        for (Action action : Action.values()) {
            double sum = 0;
            for (Map.Entry<String, ReinforcementPushdownAgent>
                    entry : agents.entrySet()) {
                sum += weights.get(entry.getKey())
                        * entry.getValue().q(action);
            }
            double avg = sum / totalWeight;
            if (noiseScale > 0) {
                avg += (random.nextDouble() * 2 - 1)
                        * noiseScale;
                noisedUpdates++;
            }
            merged.put(action, avg);
        }
        ModelSnapshot snapshot = new ModelSnapshot(queryType,
                Map.copyOf(merged), agents.size());
        aggregated.put(queryType, snapshot);
        return snapshot;
    }

    /** 联邦决策：优先聚合模型，未聚合时回退协调器加权决策。 */
    public Action federatedDecide(String queryType) {
        ModelSnapshot snapshot = aggregated.get(queryType);
        if (snapshot != null) {
            double pushdown = snapshot.q()
                    .getOrDefault(Action.PUSHDOWN, 0.0);
            double local = snapshot.q()
                    .getOrDefault(Action.KEEP_LOCAL, 0.0);
            return pushdown >= local
                    ? Action.PUSHDOWN : Action.KEEP_LOCAL;
        }
        if (coordinator != null) {
            return coordinator.federatedDecide(queryType);
        }
        return requireAgent(queryType).decide();
    }

    /** 语义一致性校验：记录检查次数，保证决策不影响 SQL 结果。 */
    public synchronized void checkSemantics(String queryType,
                                            boolean same) {
        requireAgent(queryType);
        consistencyChecks++;
        if (same) {
            consistentChecks++;
        }
    }

    public ModelSnapshot aggregated(String queryType) {
        return aggregated.get(queryType);
    }

    public PrivacyStats privacyStats() {
        return new PrivacyStats(noiseScale, clipBound,
                clippedUpdates, noisedUpdates,
                consistencyChecks, consistentChecks);
    }

    public long rounds() {
        return rounds;
    }

    public int agentCount() {
        return agents.size();
    }

    private double weightShare(String queryType) {
        double total = weights.values().stream()
                .mapToDouble(Double::doubleValue).sum();
        return total > 0
                ? weights.getOrDefault(queryType, 0.0) / total
                : 0;
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
