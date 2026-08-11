package io.tieringkv.sql.distributed;

/** 两阶段聚合局部结果（ADR-0120）。 */
public record PartialAggregate(long count, long sum, long min,
                               long max) {

    public PartialAggregate {
        if (count == 0) {
            sum = 0;
            min = Long.MAX_VALUE;
            max = Long.MIN_VALUE;
        }
    }

    public static PartialAggregate of(long value) {
        return new PartialAggregate(1, value, value, value);
    }
}
