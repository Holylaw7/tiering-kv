package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;

import java.util.List;

/** 多算子联合请求（ADR-0215）：FILTER → PROJECT → AGGREGATE 链。 */
public final class CompoundCoprocessorRequest {

    private final List<Operator> operators;
    private final String startKey;
    private final String endKey;
    private final double threshold;

    public CompoundCoprocessorRequest(List<Operator> operators,
                                      String startKey,
                                      String endKey,
                                      double threshold) {
        if (operators == null || operators.isEmpty()
                || startKey == null || endKey == null
                || startKey.isBlank() || endKey.isBlank()) {
            throw new IllegalArgumentException(
                    "operators and range required");
        }
        this.operators = List.copyOf(operators);
        this.startKey = startKey;
        this.endKey = endKey;
        this.threshold = threshold;
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
}
