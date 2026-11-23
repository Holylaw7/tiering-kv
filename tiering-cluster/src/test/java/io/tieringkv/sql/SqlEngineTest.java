package io.tieringkv.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 引擎进阶（ADR-0116）：hash join、聚合、执行计划。 */
class SqlEngineTest {

    @Test
    void hashJoinMatchesOnKey() {
        List<SqlEngine.Row> left = rows("a1", "x", "a2", "y");
        List<SqlEngine.Row> right = rows("a1", "p", "a3", "q");
        List<SqlEngine.Row> joined = new SqlEngine().hashJoin(left, right,
                SqlEngineTest::rowKey, SqlEngineTest::rowKey);
        assertThat(joined).hasSize(1);
        assertThat(new String(joined.get(0).key(),
                StandardCharsets.UTF_8)).isEqualTo("a1");
    }

    @Test
    void hashJoinNoMatchesEmpty() {
        List<SqlEngine.Row> left = rows("a1", "x");
        List<SqlEngine.Row> right = rows("b1", "y");
        assertThat(new SqlEngine().hashJoin(left, right,
                SqlEngineTest::rowKey, SqlEngineTest::rowKey)).isEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedJoinCounts(int count) {
        List<SqlEngine.Row> left = new ArrayList<>();
        List<SqlEngine.Row> right = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            left.add(new SqlEngine.Row(bytes("k" + i), bytes("L" + i)));
            right.add(new SqlEngine.Row(bytes("k" + i), bytes("R" + i)));
        }
        List<SqlEngine.Row> joined = new SqlEngine().hashJoin(left, right,
                SqlEngineTest::rowKey, SqlEngineTest::rowKey);
        assertThat(joined).hasSize(count);
    }

    @Test
    void aggregateCount() {
        List<SqlEngine.Row> rows = rows("a", "1", "b", "2");
        assertThat(new SqlEngine().aggregate(rows, AggregateType.COUNT,
                SqlEngineTest::rowValueLong)).isEqualTo(2);
    }

    @Test
    void aggregateSum() {
        List<SqlEngine.Row> rows = rows("a", "10", "b", "20");
        assertThat(new SqlEngine().aggregate(rows, AggregateType.SUM,
                SqlEngineTest::rowValueLong)).isEqualTo(30);
    }

    @Test
    void aggregateAvg() {
        List<SqlEngine.Row> rows = rows("a", "10", "b", "20", "c", "30");
        assertThat(new SqlEngine().aggregate(rows, AggregateType.AVG,
                SqlEngineTest::rowValueLong)).isEqualTo(20);
    }

    @Test
    void aggregateEmptyCountZero() {
        assertThat(new SqlEngine().aggregate(List.of(),
                AggregateType.COUNT, SqlEngineTest::rowValueLong))
                .isZero();
    }

    @Test
    void groupByCounts() {
        List<SqlEngine.Row> rows = new ArrayList<>();
        rows.add(new SqlEngine.Row(bytes("u1"), bytes("1")));
        rows.add(new SqlEngine.Row(bytes("u2"), bytes("2")));
        rows.add(new SqlEngine.Row(bytes("u1"), bytes("3")));
        Map<String, Long> groups = new SqlEngine().groupBy(rows,
                row -> new byte[]{row.key()[1]}, AggregateType.COUNT,
                SqlEngineTest::rowValueLong);
        assertThat(groups).hasSize(2);
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(strings = {"COUNT", "SUM", "AVG"})
    void parameterizedAggregateTypes(String typeName) {
        List<SqlEngine.Row> rows = rows("a", "5", "b", "15");
        AggregateType type = AggregateType.valueOf(typeName);
        long result = new SqlEngine().aggregate(rows, type,
                SqlEngineTest::rowValueLong);
        assertThat(result).isGreaterThanOrEqualTo(0);
    }

    @Test
    void explainPointScan() {
        SelectStatement statement = new SqlParser()
                .parse("SELECT * FROM kv WHERE key = 'x'");
        ExplainPlan plan = new SqlEngine().explain(statement);
        assertThat(plan.nodes()).extracting(ExplainPlan.PlanNode::type)
                .contains(ExplainPlan.NodeType.SCAN);
    }

    @Test
    void explainRangeWithLimit() {
        SelectStatement statement = new SqlParser().parse(
                "SELECT * FROM kv WHERE key >= 'a' LIMIT 10");
        ExplainPlan plan = new SqlEngine().explain(statement);
        assertThat(plan.nodes()).extracting(ExplainPlan.PlanNode::type)
                .contains(ExplainPlan.NodeType.FILTER);
    }

    @Test
    void explainFullScan() {
        ExplainPlan plan = new SqlEngine().explain(
                new SqlParser().parse("SELECT * FROM kv"));
        assertThat(plan.nodes()).hasSize(1);
        assertThat(plan.nodes().get(0).type())
                .isEqualTo(ExplainPlan.NodeType.SCAN);
    }

    private static List<SqlEngine.Row> rows(String... pairs) {
        List<SqlEngine.Row> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new SqlEngine.Row(bytes(pairs[i]),
                    bytes(pairs[i + 1])));
        }
        return list;
    }

    private static byte[] rowKey(SqlEngine.Row row) {
        return row.key();
    }

    private static long rowValueLong(SqlEngine.Row row) {
        return Long.parseLong(new String(row.value(),
                StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
