package io.tieringkv.sql.coprocessor;

import java.util.List;

/** Coprocessor 请求（ADR-0210）：算子 + 范围 + 谓词。 */
public final class CoprocessorRequest {

    /** 支持算子。 */
    public enum Operator {
        FILTER,
        PROJECT,
        AGGREGATE,
        JOIN,
        GROUP_BY,
        ORDER_BY,
        LIMIT
    }

    /** 行：键 + 值。 */
    public record Row(String key, double value) {
    }

    private final Operator operator;
    private final String startKey;
    private final String endKey;
    private final double threshold;
    private final List<String> projectColumns;

    public CoprocessorRequest(Operator operator, String startKey,
                              String endKey, double threshold,
                              List<String> projectColumns) {
        if (operator == null || startKey == null
                || endKey == null || startKey.isBlank()
                || endKey.isBlank()) {
            throw new IllegalArgumentException(
                    "operator and range required");
        }
        this.operator = operator;
        this.startKey = startKey;
        this.endKey = endKey;
        this.threshold = threshold;
        this.projectColumns = projectColumns == null
                ? List.of() : List.copyOf(projectColumns);
    }

    public Operator operator() {
        return operator;
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

    public List<String> projectColumns() {
        return projectColumns;
    }
}
