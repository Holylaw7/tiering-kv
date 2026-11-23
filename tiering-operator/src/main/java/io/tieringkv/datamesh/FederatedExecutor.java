package io.tieringkv.datamesh;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 数据网格联邦执行（ADR-0148）：跨域聚合 + JOIN。 */
public final class FederatedExecutor {

    /** 单域分片结果：指标值 + 样本数。 */
    public record ShardResult(String domainId, double value,
                              long count) {
    }

    /** 跨域聚合结果。 */
    public record AggregateResult(double value, long count) {
    }

    /** 带 key 的域行（用于 JOIN）。 */
    public record Row(String key, String domainId, double value) {
    }

    /** 跨域 JOIN 结果行。 */
    public record JoinRow(String key, double left, double right) {
    }

    public AggregateResult execute(
            FederatedPlanner.Plan plan,
            Function<FederatedPlanner.Shard, ShardResult> shardExecutor) {
        if (plan == null || shardExecutor == null) {
            throw new IllegalArgumentException(
                    "plan and executor required");
        }
        List<ShardResult> results = plan.shards().stream()
                .map(shardExecutor).toList();
        return switch (plan.aggregate()) {
            case SUM -> new AggregateResult(
                    results.stream().mapToDouble(
                            ShardResult::value).sum(),
                    results.size());
            case COUNT -> new AggregateResult(
                    results.stream().mapToLong(
                            ShardResult::count).sum(),
                    results.stream().mapToLong(
                            ShardResult::count).sum());
            case AVG -> {
                long count = results.stream().mapToLong(
                        ShardResult::count).sum();
                double total = results.stream()
                        .mapToDouble(result -> result.value()
                                * result.count()).sum();
                yield count == 0
                        ? new AggregateResult(0, 0)
                        : new AggregateResult(total / count, count);
            }
            case MIN -> new AggregateResult(
                    results.stream().mapToDouble(
                            ShardResult::value).min().orElse(0),
                    results.size());
            case MAX -> new AggregateResult(
                    results.stream().mapToDouble(
                            ShardResult::value).max().orElse(0),
                    results.size());
        };
    }

    /** 跨域 INNER JOIN：按 key 合并左右域。 */
    public List<JoinRow> join(List<Row> left, List<Row> right) {
        if (left == null || right == null) {
            throw new IllegalArgumentException(
                    "left and right rows required");
        }
        Map<String, Double> rightIndex = new LinkedHashMap<>();
        for (Row row : right) {
            rightIndex.put(row.key(), row.value());
        }
        List<JoinRow> joined = new ArrayList<>();
        for (Row row : left) {
            Double rightValue = rightIndex.get(row.key());
            if (rightValue != null) {
                joined.add(new JoinRow(row.key(), row.value(),
                        rightValue));
            }
        }
        joined.sort(Comparator.comparing(JoinRow::key));
        return joined;
    }
}
