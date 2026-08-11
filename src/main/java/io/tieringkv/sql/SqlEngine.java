package io.tieringkv.sql;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** SQL 引擎进阶（ADR-0116）：hash join、聚合、执行计划。 */
public final class SqlEngine {

    public record Row(byte[] key, byte[] value) {
    }

    /** 等值 hash join：right 按 joinKey 建哈希，left 探测。 */
    public List<Row> hashJoin(List<Row> left, List<Row> right,
                              java.util.function.Function<Row, byte[]>
                                      leftKey,
                              java.util.function.Function<Row, byte[]>
                                      rightKey) {
        Map<Key, List<Row>> rightIndex = new HashMap<>();
        for (Row row : right) {
            rightIndex.computeIfAbsent(new Key(rightKey.apply(row)),
                    ignored -> new ArrayList<>()).add(row);
        }
        List<Row> result = new ArrayList<>();
        for (Row row : left) {
            List<Row> matches = rightIndex.get(
                    new Key(leftKey.apply(row)));
            if (matches != null) {
                for (Row match : matches) {
                    result.add(new Row(row.key(),
                            joinValue(row, match)));
                }
            }
        }
        return result;
    }

    public long aggregate(List<Row> rows, AggregateType type,
                          java.util.function.ToLongFunction<Row> extractor) {
        if (rows.isEmpty() && type != AggregateType.COUNT) {
            return 0;
        }
        return switch (type) {
            case COUNT -> rows.size();
            case SUM -> rows.stream().mapToLong(extractor).sum();
            case AVG -> rows.isEmpty() ? 0
                    : rows.stream().mapToLong(extractor).sum()
                    / rows.size();
        };
    }

    public Map<byte[], Long> groupBy(List<Row> rows,
                                     java.util.function.Function<Row,
                                             byte[]> groupKey,
                                     AggregateType type,
                                     java.util.function.ToLongFunction<Row>
                                             extractor) {
        Map<Key, List<Row>> groups = new HashMap<>();
        for (Row row : rows) {
            groups.computeIfAbsent(new Key(groupKey.apply(row)),
                    ignored -> new ArrayList<>()).add(row);
        }
        Map<byte[], Long> result = new HashMap<>();
        groups.forEach((key, group) -> result.put(key.bytes(),
                aggregate(group, type, extractor)));
        return result;
    }

    public ExplainPlan explain(SelectStatement statement) {
        List<ExplainPlan.PlanNode> nodes = new ArrayList<>();
        nodes.add(new ExplainPlan.PlanNode(ExplainPlan.NodeType.SCAN,
                statement.exactKey() != null ? "point"
                        : "range"));
        if (statement.limit() < Integer.MAX_VALUE) {
            nodes.add(new ExplainPlan.PlanNode(
                    ExplainPlan.NodeType.FILTER, "limit "
                            + statement.limit()));
        }
        return new ExplainPlan(nodes);
    }

    private static byte[] joinValue(Row left, Row right) {
        String leftValue = new String(left.value(),
                StandardCharsets.UTF_8);
        String rightValue = new String(right.value(),
                StandardCharsets.UTF_8);
        return (leftValue + "|" + rightValue)
                .getBytes(StandardCharsets.UTF_8);
    }

    private record Key(byte[] bytes) {
        private Key {
            bytes = bytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && java.util.Arrays.equals(bytes, that.bytes);
        }

        @Override
        public int hashCode() {
            return java.util.Arrays.hashCode(bytes);
        }

    }
}
