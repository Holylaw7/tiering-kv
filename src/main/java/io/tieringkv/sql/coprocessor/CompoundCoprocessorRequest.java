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
    private final int limit;
    private final boolean orderDescending;

    public CompoundCoprocessorRequest(List<Operator> operators,
                                      String startKey,
                                      String endKey,
                                      double threshold) {
        this(operators, startKey, endKey, threshold, List.of(),
                Integer.MAX_VALUE, false);
    }

    public CompoundCoprocessorRequest(List<Operator> operators,
                                      String startKey,
                                      String endKey,
                                      double threshold,
                                      List<Row> joinRows,
                                      int limit,
                                      boolean orderDescending) {
        if (operators == null || operators.isEmpty()
                || startKey == null || endKey == null
                || startKey.isBlank() || endKey.isBlank()
                || joinRows == null || limit < 0) {
            throw new IllegalArgumentException(
                    "operators, range, joinRows and non-negative "
                            + "limit required");
        }
        this.operators = List.copyOf(operators);
        this.startKey = startKey;
        this.endKey = endKey;
        this.threshold = threshold;
        this.joinRows = List.copyOf(joinRows);
        this.limit = limit;
        this.orderDescending = orderDescending;
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

    public int limit() {
        return limit;
    }

    public boolean orderDescending() {
        return orderDescending;
    }
}
