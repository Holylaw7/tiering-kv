package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.ParetoCapacityOptimizer.Candidate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 在线 Pareto 重平衡（ADR-0198）：指标流 → 前沿更新。 */
public final class OnlineParetoRebalancer {

    /** 重平衡记录。 */
    public record Rebalance(int round, int frontSize,
                            String recommended) {
    }

    private final ParetoCapacityOptimizer optimizer =
            new ParetoCapacityOptimizer();
    private final int maxNodeChange;
    private final List<Rebalance> history =
            new CopyOnWriteArrayList<>();
    private volatile int round;

    public OnlineParetoRebalancer(int maxNodeChange) {
        if (maxNodeChange < 1) {
            throw new IllegalArgumentException(
                    "max node change must be positive");
        }
        this.maxNodeChange = maxNodeChange;
    }

    /** 周期重算：前沿 + 权重推荐（限幅）。 */
    public Rebalance rebalance(List<Candidate> candidates,
                               Candidate current,
                               double wSlo, double wCost,
                               double wRisk) {
        if (candidates == null || candidates.isEmpty()
                || current == null) {
            throw new IllegalArgumentException(
                    "candidates and current required");
        }
        List<Candidate> front = optimizer.paretoFront(candidates);
        Candidate chosen = optimizer.chooseByWeights(front,
                wSlo, wCost, wRisk);
        int delta = Math.abs(chosen.nodes() - current.nodes());
        String recommended = delta <= maxNodeChange
                ? chosen.name() : current.name();
        Rebalance record = new Rebalance(round++, front.size(),
                recommended);
        history.add(record);
        return record;
    }

    public List<Rebalance> history() {
        return List.copyOf(history);
    }
}
