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
        };
    }

    /** 执行多算子链：FILTER → PROJECT → AGGREGATE 顺序应用。 */
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
        for (Operator operator : request.operators()) {
            CoprocessorRequest step = new CoprocessorRequest(
                    operator, request.startKey(), request.endKey(),
                    request.threshold(), List.of());
            current = execute(step, current);
        }
        return current;
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
