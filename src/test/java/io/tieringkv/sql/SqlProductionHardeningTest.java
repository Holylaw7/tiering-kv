package io.tieringkv.sql;

import io.tieringkv.sql.SqlProductionSupport.SqlError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 生产化（ADR-0295）：错误语义 + EXPLAIN。 */
class SqlProductionHardeningTest {

    @Test
    void syntaxErrorMapped() {
        SqlError error = SqlProductionSupport.errorOf(
                new IllegalArgumentException("syntax error "
                        + "near SELECT"));
        assertThat(error.code()).isEqualTo("SYNTAX_ERROR");
    }

    @Test
    void unknownColumnMapped() {
        SqlError error = SqlProductionSupport.errorOf(
                new IllegalArgumentException(
                        "unknown column name"));
        assertThat(error.code()).isEqualTo("UNKNOWN_COLUMN");
    }

    @Test
    void typeErrorMapped() {
        SqlError error = SqlProductionSupport.errorOf(
                new ClassCastException("cannot cast type"));
        assertThat(error.code()).isEqualTo("TYPE_ERROR");
    }

    @Test
    void internalErrorMapped() {
        SqlError error = SqlProductionSupport.errorOf(
                new RuntimeException("boom"));
        assertThat(error.code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void explainContainsAllNodeTypes() {
        ExplainPlan plan = new ExplainPlan(List.of(
                new ExplainPlan.PlanNode(
                        ExplainPlan.NodeType.SCAN, "t1"),
                new ExplainPlan.PlanNode(
                        ExplainPlan.NodeType.JOIN, "hash"),
                new ExplainPlan.PlanNode(
                        ExplainPlan.NodeType.AGGREGATE, "sum")));
        String explain = SqlProductionSupport.explain(plan);
        assertThat(explain).contains("SCAN", "JOIN", "AGGREGATE",
                "summary", "pushdown=available");
    }

    @Test
    void explainIndentsNodes() {
        ExplainPlan plan = new ExplainPlan(List.of(
                new ExplainPlan.PlanNode(
                        ExplainPlan.NodeType.SCAN, "t")));
        String explain = SqlProductionSupport.explain(plan);
        assertThat(explain).contains("  0: SCAN t");
    }

    @ParameterizedTest(name = "error {0}")
    @MethodSource("errors")
    void errorMatrix(String message, String expectedCode) {
        SqlError error = SqlProductionSupport.errorOf(
                new IllegalArgumentException(message));
        assertThat(error.code()).isEqualTo(expectedCode);
    }

    @ParameterizedTest(name = "plan {0}")
    @MethodSource("plans")
    void explainMatrix(List<ExplainPlan.PlanNode> nodes,
                       String expected) {
        String explain = SqlProductionSupport.explain(
                new ExplainPlan(nodes));
        assertThat(explain).contains(expected);
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of("syntax error", "SYNTAX_ERROR"),
                Arguments.of("parse failed", "SYNTAX_ERROR"),
                Arguments.of("unknown column x", "UNKNOWN_COLUMN"),
                Arguments.of("field not found", "UNKNOWN_COLUMN"),
                Arguments.of("type mismatch", "TYPE_ERROR"),
                Arguments.of("cannot cast", "TYPE_ERROR"),
                Arguments.of("generic failure", "INTERNAL_ERROR"),
                Arguments.of("disk io error", "INTERNAL_ERROR"),
                Arguments.of("column type wrong", "TYPE_ERROR"),
                Arguments.of("syntax near WHERE", "SYNTAX_ERROR"));
    }

    static Stream<Arguments> plans() {
        return Stream.of(
                Arguments.of(List.of(new ExplainPlan.PlanNode(
                        ExplainPlan.NodeType.SCAN, "t")), "SCAN"),
                Arguments.of(List.of(
                        new ExplainPlan.PlanNode(
                                ExplainPlan.NodeType.JOIN,
                                "hash")), "JOIN"),
                Arguments.of(List.of(
                        new ExplainPlan.PlanNode(
                                ExplainPlan.NodeType.AGGREGATE,
                                "count")), "AGGREGATE"),
                Arguments.of(List.of(
                        new ExplainPlan.PlanNode(
                                ExplainPlan.NodeType.FILTER,
                                "age>18")), "FILTER"),
                Arguments.of(List.of(
                        new ExplainPlan.PlanNode(
                                ExplainPlan.NodeType.SCAN, "a"),
                        new ExplainPlan.PlanNode(
                                ExplainPlan.NodeType.JOIN,
                                "b")), "JOIN"),
                Arguments.of(List.of(), "EXPLAIN PLAN"));
    }
}
