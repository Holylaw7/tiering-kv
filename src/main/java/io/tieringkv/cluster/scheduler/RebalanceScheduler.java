package io.tieringkv.cluster.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 均衡调度（ADR-0205）：负载不均 → 迁移计划（epoch 保护）。 */
public final class RebalanceScheduler {

    /** 迁移动作：源节点 → 目标节点 + 负载量。 */
    public record Move(String from, String to, long amount) {
    }

    /** 生成均衡计划：超载节点 → 低载节点迁移。 */
    public List<Move> plan(Map<String, Long> loads,
                           long maxLoad) {
        if (loads == null || loads.isEmpty()) {
            throw new IllegalArgumentException(
                    "loads required");
        }
        List<Move> moves = new ArrayList<>();
        List<Map.Entry<String, Long>> overloaded =
                loads.entrySet().stream()
                        .filter(entry -> entry.getValue() > maxLoad)
                        .toList();
        List<Map.Entry<String, Long>> underloaded =
                loads.entrySet().stream()
                        .filter(entry -> entry.getValue() < maxLoad)
                        .toList();
        for (Map.Entry<String, Long> source : overloaded) {
            for (Map.Entry<String, Long> target : underloaded) {
                long excess = source.getValue() - maxLoad;
                long capacity = maxLoad - target.getValue();
                long amount = Math.min(excess, capacity);
                if (amount > 0) {
                    moves.add(new Move(source.getKey(),
                            target.getKey(), amount));
                }
            }
        }
        return moves;
    }
}
