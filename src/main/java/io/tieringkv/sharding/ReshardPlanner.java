package io.tieringkv.sharding;

import java.util.ArrayList;
import java.util.List;

/** 重分片计划（ADR-0126）：拆分与合并。 */
public final class ReshardPlanner {

    public record SplitPlan(int sourceShard, int targetShards) {
    }

    public record MergePlan(int fromShard, int toShard) {
    }

    public List<SplitPlan> split(int sourceShard, int targetShards) {
        if (targetShards < 2) {
            throw new IllegalArgumentException(
                    "targetShards must be >= 2");
        }
        List<SplitPlan> plans = new ArrayList<>();
        for (int i = 0; i < targetShards; i++) {
            plans.add(new SplitPlan(sourceShard, i));
        }
        return plans;
    }

    public MergePlan merge(int fromShard, int toShard) {
        if (fromShard == toShard) {
            throw new IllegalArgumentException(
                    "cannot merge into itself");
        }
        return new MergePlan(fromShard, toShard);
    }
}
