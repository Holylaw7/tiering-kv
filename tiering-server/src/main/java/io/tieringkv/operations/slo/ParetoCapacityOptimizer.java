package io.tieringkv.operations.slo;

import java.util.ArrayList;
import java.util.List;

/** Pareto 容量优化（ADR-0191）：SLO × 成本 × 风险前沿。 */
public final class ParetoCapacityOptimizer {

    /** 候选方案：节点数 + 三目标评分（0~1）。 */
    public record Candidate(String name, int nodes,
                            double sloScore, double costScore,
                            double riskScore) {

        public Candidate {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(
                        "name required");
            }
            if (nodes < 1 || sloScore < 0 || sloScore > 1
                    || costScore < 0 || costScore > 1
                    || riskScore < 0 || riskScore > 1) {
                throw new IllegalArgumentException(
                        "invalid candidate");
            }
        }
    }

    /** a 支配 b：三目标都不差且至少一项更优。 */
    public boolean dominates(Candidate a, Candidate b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException(
                    "candidates required");
        }
        boolean noWorse = a.sloScore() >= b.sloScore()
                && a.costScore() <= b.costScore()
                && a.riskScore() <= b.riskScore();
        boolean strict = a.sloScore() > b.sloScore()
                || a.costScore() < b.costScore()
                || a.riskScore() < b.riskScore();
        return noWorse && strict;
    }

    /** 计算 Pareto 前沿（未被支配的候选）。 */
    public List<Candidate> paretoFront(
            List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "candidates required");
        }
        List<Candidate> front = new ArrayList<>();
        for (Candidate candidate : candidates) {
            boolean dominated = false;
            for (Candidate other : candidates) {
                if (other != candidate && dominates(other,
                        candidate)) {
                    dominated = true;
                    break;
                }
            }
            if (!dominated) {
                front.add(candidate);
            }
        }
        return front;
    }

    /** 权重选择：最大化 wSlo×slo - wCost×cost - wRisk×risk。 */
    public Candidate chooseByWeights(List<Candidate> front,
                                     double wSlo, double wCost,
                                     double wRisk) {
        if (front == null || front.isEmpty()) {
            throw new IllegalArgumentException(
                    "front required");
        }
        if (wSlo < 0 || wCost < 0 || wRisk < 0) {
            throw new IllegalArgumentException(
                    "weights must be non-negative");
        }
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : front) {
            double score = wSlo * candidate.sloScore()
                    - wCost * candidate.costScore()
                    - wRisk * candidate.riskScore();
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }
}
