package io.tieringkv.cluster.placement;

import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;

/** 迁移/转移计划项（ADR-0065）：携带生成时 epoch 供执行前校验。 */
public record RegionMove(
        RegionId regionId,
        String fromNode,
        String toNode,
        String reason,
        boolean leaderMove,
        RegionEpoch epoch) {
}
