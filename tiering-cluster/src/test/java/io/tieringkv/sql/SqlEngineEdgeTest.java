package io.tieringkv.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 引擎边缘（ADR-0116）：JOIN/聚合/分组参数矩阵。 */
class SqlEngineEdgeTest {

    @ParameterizedTest(name = "left {0} right {1}")
    @ValueSource(ints = {1, 50})
    void joinSymmetric(int count) {
        List<SqlEngine.Row> left = rows(count, "L");
        List<SqlEngine.Row> right = rows(count, "R");
        SqlEngine engine = new SqlEngine();
        List<SqlEngine.Row> forward = engine.hashJoin(left, right,
                SqlEngine.Row::key, SqlEngine.Row::key);
        List<SqlEngine.Row> reverse = engine.hashJoin(right, left,
                SqlEngine.Row::key, SqlEngine.Row::key);
        assertThat(forward).hasSize(count);
        assertThat(reverse).hasSize(count);
    }

    @Test
    void joinRightEmpty() {
        SqlEngine engine = new SqlEngine();
        assertThat(engine.hashJoin(rows(5, "L"), List.of(),
                SqlEngine.Row::key, SqlEngine.Row::key)).isEmpty();
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {0, 1, 100})
    void aggregateEdgeCounts(int count) {
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new SqlEngine.Row(bytes("k" + i),
                    bytes(String.valueOf(i))));
        }
        SqlEngine engine = new SqlEngine();
        assertThat(engine.aggregate(rows, AggregateType.COUNT,
                SqlEngineEdgeTest::value)).isEqualTo(count);
        assertThat(engine.aggregate(rows, AggregateType.SUM,
                SqlEngineEdgeTest::value)).isGreaterThanOrEqualTo(0);
    }

    @Test
    void groupBySingleGroup() {
        SqlEngine engine = new SqlEngine();
        Map<String, Long> groups = engine.groupBy(rows(5, "V"),
                row -> bytes("all"), AggregateType.COUNT,
                SqlEngineEdgeTest::value);
        assertThat(groups).hasSize(1);
        assertThat(groups.get(new String(bytes("all"),
                StandardCharsets.ISO_8859_1))).isEqualTo(5);
    }

    @ParameterizedTest(name = "groups {0}")
    @ValueSource(ints = {1, 3, 10})
    void groupByMultiGroups(int groupCount) {
        SqlEngine engine = new SqlEngine();
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < groupCount * 2; i++) {
            rows.add(new SqlEngine.Row(
                    bytes("g" + (i % groupCount)),
                    bytes(String.valueOf(i))));
        }
        Map<String, Long> groups = engine.groupBy(rows,
                row -> new byte[]{row.key()[1]}, AggregateType.COUNT,
                SqlEngineEdgeTest::value);
        assertThat(groups).hasSize(groupCount);
    }

    @Test
    void explainScanAndFilter() {
        SqlEngine engine = new SqlEngine();
        ExplainPlan plan = engine.explain(new SqlParser().parse(
                "SELECT * FROM kv WHERE key >= 'a' LIMIT 3"));
        assertThat(plan.nodes()).hasSize(2);
        assertThat(plan.nodes().get(0).type())
                .isEqualTo(ExplainPlan.NodeType.SCAN);
        assertThat(plan.nodes().get(1).type())
                .isEqualTo(ExplainPlan.NodeType.FILTER);
    }

    private static List<SqlEngine.Row> rows(int count, String prefix) {
        List<SqlEngine.Row> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new SqlEngine.Row(bytes("k" + i),
                    bytes(prefix + i)));
        }
        return list;
    }

    private static long value(SqlEngine.Row row) {
        String value = new String(row.value(), StandardCharsets.UTF_8)
                .replaceAll("\\D", "");
        return value.isEmpty() ? 0 : Long.parseLong(value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
