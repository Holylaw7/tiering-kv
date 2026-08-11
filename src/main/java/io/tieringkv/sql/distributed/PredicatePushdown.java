package io.tieringkv.sql.distributed;

import java.util.List;

/** 谓词下推（Goal 7）：按 key 范围裁剪分片计划。 */
public final class PredicatePushdown {

    public List<ShardPlan> filter(List<ShardPlan> plans,
                                  byte[] requiredStart,
                                  byte[] requiredEnd) {
        return plans.stream()
                .filter(plan -> intersects(plan, requiredStart,
                        requiredEnd))
                .toList();
    }

    private static boolean intersects(ShardPlan plan,
                                      byte[] start, byte[] end) {
        if (end != null && java.util.Arrays.compareUnsigned(
                plan.startKey(), end) >= 0) {
            return false;
        }
        if (start != null && java.util.Arrays.compareUnsigned(
                plan.endKey(), start) <= 0) {
            return false;
        }
        return true;
    }
}
