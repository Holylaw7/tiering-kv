package io.tieringkv.sql.distributed;

import io.tieringkv.sql.SqlEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** 分布式执行器（ADR-0120）：按分片计划下发并合并。 */
public final class DistributedExecutor {

    public List<SqlEngine.Row> execute(List<ShardPlan> plans,
                                       Function<ShardPlan,
                                               List<SqlEngine.Row>>
                                               regionExecutor) {
        List<List<SqlEngine.Row>> results = new ArrayList<>();
        for (ShardPlan plan : plans) {
            results.add(regionExecutor.apply(plan));
        }
        return new MergeJoin().merge(results);
    }
}
