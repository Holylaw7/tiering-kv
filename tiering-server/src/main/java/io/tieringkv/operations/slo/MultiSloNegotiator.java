package io.tieringkv.operations.slo;

import java.util.List;

/** 多 SLO 预算谈判（ADR-0177）：加权缺口 + 最差优先。 */
public final class MultiSloNegotiator {

    public enum Action {
        SCALE_UP,
        MAINTAIN
    }

    /** 单 SLO 输入：达成率 + 目标 + 权重。 */
    public record SloInput(String sloId, double attainment,
                           double target, double weight) {

        public SloInput {
            if (sloId == null || sloId.isBlank()) {
                throw new IllegalArgumentException(
                        "sloId required");
            }
            if (attainment < 0 || attainment > 1
                    || target <= 0 || target > 1) {
                throw new IllegalArgumentException(
                        "attainment/target invalid");
            }
            if (weight < 0) {
                throw new IllegalArgumentException(
                        "weight must be non-negative");
            }
        }
    }

    /** 谈判结果：缺口 + 建议节点 + 动作。 */
    public record NegotiationPlan(double worstDeficit,
                                  double weightedDeficit,
                                  String worstSloId,
                                  int suggestedNodes,
                                  Action action) {
    }

    private final double headroomFactor;

    public MultiSloNegotiator() {
        this(2.0);
    }

    public MultiSloNegotiator(double headroomFactor) {
        if (headroomFactor < 1) {
            throw new IllegalArgumentException(
                    "headroom factor must be >= 1");
        }
        this.headroomFactor = headroomFactor;
    }

    /** 多 SLO 联合谈判：最差优先 + 加权缺口。 */
    public NegotiationPlan negotiate(List<SloInput> inputs,
                                     int currentNodes,
                                     int maxNodes) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException(
                    "inputs required");
        }
        if (currentNodes < 1) {
            throw new IllegalArgumentException(
                    "currentNodes must be positive");
        }
        if (maxNodes < currentNodes) {
            throw new IllegalArgumentException(
                    "maxNodes must be >= currentNodes");
        }
        double worstDeficit = 0;
        String worstSloId = null;
        double weightedSum = 0;
        double weightTotal = 0;
        for (SloInput input : inputs) {
            double deficit = Math.max(0,
                    (input.target() - input.attainment())
                            / input.target());
            weightedSum += input.weight() * deficit;
            weightTotal += input.weight();
            if (deficit > worstDeficit) {
                worstDeficit = deficit;
                worstSloId = input.sloId();
            }
        }
        double weighted = weightTotal == 0 ? worstDeficit
                : weightedSum / weightTotal;
        if (worstDeficit == 0) {
            return new NegotiationPlan(0, weighted, worstSloId,
                    currentNodes, Action.MAINTAIN);
        }
        int increase = (int) Math.ceil(
                currentNodes * weighted * headroomFactor);
        int suggested = Math.min(maxNodes,
                currentNodes + Math.max(1, increase));
        return new NegotiationPlan(worstDeficit, weighted,
                worstSloId, suggested, Action.SCALE_UP);
    }
}
