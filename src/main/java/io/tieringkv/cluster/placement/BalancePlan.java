package io.tieringkv.cluster.placement;

import java.util.List;

/** 均衡计划（ADR-0065）：迁移列表 + 均衡度快照。 */
public record BalancePlan(
        List<RegionMove> moves,
        boolean balanced,
        int regionSkew,
        int leaderSkew) {

    public BalancePlan {
        moves = List.copyOf(moves);
    }
}
