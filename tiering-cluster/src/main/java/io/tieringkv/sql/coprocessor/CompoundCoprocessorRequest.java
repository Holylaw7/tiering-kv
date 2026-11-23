package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;

import java.util.List;

/** 多算子联合请求（ADR-0215）：FILTER → PROJECT → AGGREGATE 链。 */
public final class CompoundCoprocessorRequest {

    private final List<Operator> operators;
    private final String startKey;
    private final String endKey;
    private final double threshold;
    private final List<Row> joinRows;
    private final List<List<Row>> joinTables;
    private final int limit;
    private final boolean orderDescending;
    private final WindowFunction windowFunction;

    /** 窗口函数类型（ADR-0229）。 */
    public enum WindowFunction {
        NONE,
        ROW_NUMBER,
        RANK,
        LAG,
        LEAD,
        SUM_OVER,
        COUNT_OVER,
        AVG_OVER
    }

    public CompoundCoprocessorRequest(List<Operator> operators,
                                      String startKey,
                                      String endKey,
                                      double threshold) {
        this(operators, startKey, endKey, threshold, List.of(),
                List.of(), Integer.MAX_VALUE, false,
                WindowFunction.NONE);
    }

    public CompoundCoprocessorRequest(List<Operator> operators,
                                      String startKey,
                                      String endKey,
                                      double threshold,
                                      List<Row> joinRows,
                                      int limit,
                                      boolean orderDescending) {
        this(operators, startKey, endKey, threshold, joinRows,
                List.of(), limit, orderDescending,
                WindowFunction.NONE);
    }

    public CompoundCoprocessorRequest(List<Operator> operators,
                                      String startKey,
                                      String endKey,
                                      double threshold,
                                      List<Row> joinRows,
                                      List<List<Row>> joinTables,
                                      int limit,
                                      boolean orderDescending,
                                      WindowFunction windowFunction) {
        if (operators == null || operators.isEmpty()
                || startKey == null || endKey == null
                || startKey.isBlank() || endKey.isBlank()
                || joinRows == null || joinTables == null
                || limit < 0 || windowFunction == null) {
            throw new IllegalArgumentException(
                    "operators, range, joinRows, joinTables, "
                            + "non-negative limit and window "
                            + "function required");
        }
        this.operators = List.copyOf(operators);
        this.startKey = startKey;
        this.endKey = endKey;
        this.threshold = threshold;
        this.joinRows = List.copyOf(joinRows);
        this.joinTables = joinTables.stream()
                .map(List::copyOf).toList();
        this.limit = limit;
        this.orderDescending = orderDescending;
        this.windowFunction = windowFunction;
    }

    public List<Operator> operators() {
        return operators;
    }

    public String startKey() {
        return startKey;
    }

    public String endKey() {
        return endKey;
    }

    public double threshold() {
        return threshold;
    }

    public List<Row> joinRows() {
        return joinRows;
    }

    public List<List<Row>> joinTables() {
        return joinTables;
    }

    public int limit() {
        return limit;
    }

    public boolean orderDescending() {
        return orderDescending;
    }

    public WindowFunction windowFunction() {
        return windowFunction;
    }
}
