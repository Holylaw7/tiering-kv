package io.tieringkv.sql.distributed;

import io.tieringkv.sql.AggregateType;

import java.util.List;

/** 聚合合并（ADR-0120）：partial → 全局 COUNT/SUM/AVG。 */
public final class MergeAggregate {

    public long merge(List<PartialAggregate> partials,
                      AggregateType type) {
        long count = 0;
        long sum = 0;
        for (PartialAggregate partial : partials) {
            count += partial.count();
            sum += partial.sum();
        }
        return switch (type) {
            case COUNT -> count;
            case SUM -> sum;
            case AVG -> count == 0 ? 0 : sum / count;
        };
    }
}
