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
            case WINDOW -> inRange;
        };
    }

    /**
     * 执行多算子链：固定顺序 JOIN → FILTER → PROJECT → AGGREGATE →
     * GROUP_BY → WINDOW → ORDER_BY → LIMIT（ADR-0222/0229），与上层
     * SQL 语义一致；同一算子出现多次时按出现次数重复应用。
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
        current = applyRepeated(current, operators,
                Operator.JOIN, request);
        current = applyRepeated(current, operators,
                Operator.FILTER, request);
        current = applyRepeated(current, operators,
                Operator.PROJECT, request);
        current = applyRepeated(current, operators,
                Operator.AGGREGATE, request);
        current = applyRepeated(current, operators,
                Operator.GROUP_BY, request);
        current = applyRepeated(current, operators,
                Operator.WINDOW, request);
        current = applyRepeated(current, operators,
                Operator.ORDER_BY, request);
        current = applyRepeated(current, operators,
                Operator.LIMIT, request);
        return current;
    }

    /** 按固定顺序应用算子，同一算子按出现次数重复。 */
    private static List<Row> applyRepeated(
            List<Row> rows, List<Operator> operators,
            Operator operator,
            CompoundCoprocessorRequest request) {
        long count = operators.stream()
                .filter(operator::equals).count();
        List<Row> current = rows;
        for (int i = 0; i < count; i++) {
            current = switch (operator) {
                case JOIN -> joinTables(
                        join(current, request.joinRows()),
                        request);
                case FILTER -> filter(current,
                        request.threshold());
                case PROJECT -> project(current,
                        request.threshold());
                case AGGREGATE -> aggregate(current);
                case GROUP_BY -> groupBy(current);
                case WINDOW -> window(current,
                        request.windowFunction());
                case ORDER_BY -> orderBy(current,
                        request.orderDescending());
                case LIMIT -> limit(current, request.limit());
            };
        }
        return current;
    }

    private static List<Row> filter(List<Row> rows,
                                    double threshold) {
        return rows.stream()
                .filter(row -> row.value() >= threshold)
                .toList();
    }

    private static List<Row> project(List<Row> rows,
                                     double factor) {
        return rows.stream()
                .map(row -> new Row(row.key(),
                        row.value() * factor))
                .toList();
    }

    private static List<Row> orderBy(List<Row> rows,
                                     boolean descending) {
        return rows.stream()
                .sorted(descending
                        ? Comparator.comparingDouble(
                                Row::value).reversed()
                        : Comparator.comparingDouble(Row::value))
                .toList();
    }

    /**
     * 窗口函数：按 key 分区、value 升序（ADR-0229）。
     * ROW_NUMBER：组内连续编号；RANK：同值同排名。
     */
    private static List<Row> window(
            List<Row> rows,
            CompoundCoprocessorRequest.WindowFunction function) {
        if (function == CompoundCoprocessorRequest
                .WindowFunction.NONE) {
            return rows;
        }
        List<Row> sorted = rows.stream()
                .sorted(Comparator.comparing(Row::key)
                        .thenComparingDouble(Row::value))
                .toList();
        List<Row> result = new ArrayList<>();
        int index = 0;
        while (index < sorted.size()) {
            String partition = sorted.get(index).key();
            int end = index;
            while (end < sorted.size()
                    && sorted.get(end).key()
                    .equals(partition)) {
                end++;
            }
            int position = 0;
            int distinct = 0;
            double previous = Double.NaN;
            while (index < end) {
                double value = sorted.get(index).value();
                position++;
                if (!Double.isNaN(previous)
                        && value != previous) {
                    distinct++;
                }
                double number = function == CompoundCoprocessorRequest
                        .WindowFunction.ROW_NUMBER
                        ? position : distinct + 1;
                result.add(new Row(partition, number));
                previous = value;
                index++;
            }
        }
        return result;
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

    /** 多表等值内连接：按请求顺序依次连接附加表。 */
    private static List<Row> joinTables(
            List<Row> rows,
            CompoundCoprocessorRequest request) {
        List<Row> current = rows;
        for (List<Row> table : request.joinTables()) {
            current = join(current, table);
        }
        return current;
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
