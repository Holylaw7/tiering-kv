package io.tieringkv.sql.distributed;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 分片计划器（ADR-0120）：按 key 前缀切分并轮询分配 Region。 */
public final class ShardPlanner {

    public List<ShardPlan> plan(List<String> regions, int shardCount,
                                String prefix) {
        if (regions.isEmpty() || shardCount < 1) {
            throw new IllegalArgumentException(
                    "regions and shardCount required");
        }
        List<ShardPlan> plans = new ArrayList<>();
        for (int i = 0; i < shardCount; i++) {
            String start = prefix + String.format("%06d", i);
            String end = prefix + String.format("%06d", i + 1);
            String region = regions.get(i % regions.size());
            plans.add(new ShardPlan(region,
                    start.getBytes(StandardCharsets.UTF_8),
                    end.getBytes(StandardCharsets.UTF_8)));
        }
        return plans;
    }

    public int shardFor(byte[] key, int shardCount, String prefix) {
        String text = new String(key, StandardCharsets.UTF_8);
        if (!text.startsWith(prefix)) {
            throw new IllegalArgumentException(
                    "key outside planned prefix");
        }
        int index = (int) (Math.abs((long) text.hashCode())
                % shardCount);
        return index;
    }
}
