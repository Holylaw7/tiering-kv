package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Coprocessor 执行（ADR-0210）：存储层算子执行。 */
public final class CoprocessorExecutor {

    /** 执行请求：范围过滤 + 算子。 */
    public List<Row> execute(CoprocessorRequest request,
                             List<Row> rows) {
        if (request == null || rows == null) {
            throw new IllegalArgumentException(
                    "request and rows required");
        }
        List<Row> inRange = rows.stream()
                .filter(row -> row.key().compareTo(
                        request.startKey()) >= 0
                        && row.key().compareTo(
                        request.endKey()) < 0)
                .toList();
        return switch (request.operator()) {
            case FILTER -> inRange.stream()
                    .filter(row -> row.value()
                            >= request.threshold())
                    .toList();
            case PROJECT -> inRange.stream()
                    .map(row -> new Row(row.key(),
                            row.value() * request.threshold()))
                    .toList();
            case AGGREGATE -> aggregate(inRange);
            case JOIN -> inRange;
            case GROUP_BY -> groupBy(inRange);
            case ORDER_BY -> inRange.stream()
                    .sorted(Comparator.comparingDouble(
                            Row::value))
                    .toList();
            case LIMIT -> inRange;
        };
    }

    /**
     * 执行多算子链：固定顺序 JOIN → FILTER → PROJECT → GROUP_BY →
     * ORDER_BY → LIMIT（ADR-0222），与上层 SQL 语义一致。
     */
    public List<Row> executeCompound(
            CompoundCoprocessorRequest request, List<Row> rows) {
        if (request == null || rows == null) {
            throw new IllegalArgumentException(
                    "request and rows required");
        }
        List<Row> current = rows.stream()
                .filter(row -> row.key().compareTo(
                        request.startKey()) >= 0
                        && row.key().compareTo(
                        request.endKey()) < 0)
                .toList();
        List<Operator> operators = request.operators();
        if (operators.contains(Operator.JOIN)) {
            current = join(current, request.joinRows());
        }
        if (operators.contains(Operator.FILTER)) {
            current = execute(new CoprocessorRequest(
                    Operator.FILTER, request.startKey(),
                    request.endKey(), request.threshold(),
                    List.of()), current);
        }
        if (operators.contains(Operator.PROJECT)) {
            current = execute(new CoprocessorRequest(
                    Operator.PROJECT, request.startKey(),
                    request.endKey(), request.threshold(),
                    List.of()), current);
        }
        if (operators.contains(Operator.GROUP_BY)) {
            current = groupBy(current);
        }
        if (operators.contains(Operator.ORDER_BY)) {
            current = current.stream()
                    .sorted(request.orderDescending()
                            ? Comparator.comparingDouble(
                                    Row::value).reversed()
                            : Comparator.comparingDouble(
                                    Row::value))
                    .toList();
        }
        if (operators.contains(Operator.LIMIT)) {
            current = limit(current, request.limit());
        }
        return current;
    }

    /** 等值内连接：key 相等，value 相加（ADR-0222）。 */
    private static List<Row> join(List<Row> left, List<Row> right) {
        if (right.isEmpty()) {
            return left;
        }
        List<Row> result = new ArrayList<>();
        for (Row l : left) {
            for (Row r : right) {
                if (l.key().equals(r.key())) {
                    result.add(new Row(l.key(),
                            l.value() + r.value()));
                }
            }
        }
        return result;
    }

    /** 分组聚合：按 key 分组求和。 */
    private static List<Row> groupBy(List<Row> rows) {
        java.util.Map<String, Double> groups =
                new java.util.LinkedHashMap<>();
        for (Row row : rows) {
            groups.merge(row.key(), row.value(), Double::sum);
        }
        List<Row> result = new ArrayList<>();
        groups.forEach((key, value) ->
                result.add(new Row(key, value)));
        return result;
    }

    /** 截断：limit 为 0 返回空，否则取前 limit 行。 */
    private static List<Row> limit(List<Row> rows, int limit) {
        if (limit == Integer.MAX_VALUE) {
            return rows;
        }
        return rows.stream().limit(limit).toList();
    }

    private static List<Row> aggregate(List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of(new Row("sum", 0));
        }
        double sum = rows.stream().mapToDouble(Row::value).sum();
        return List.of(new Row("sum", sum));
    }

    /** 排序结果（用于断言确定性）。 */
    public List<Row> sorted(List<Row> rows) {
        return rows.stream().sorted(
                Comparator.comparing(Row::key)).toList();
    }
}
