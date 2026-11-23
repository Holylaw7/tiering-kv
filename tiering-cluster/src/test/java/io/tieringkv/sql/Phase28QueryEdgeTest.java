package io.tieringkv.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 SQL 查询边缘（ADR-0116）：JOIN/聚合参数矩阵。 */
class Phase28QueryEdgeTest {

    @ParameterizedTest(name = "left {0} right {1}")
    @ValueSource(ints = {10, 200})
    void joinManyToMany(int leftCount) {
        List<SqlEngine.Row> left = rows(leftCount, "L");
        List<SqlEngine.Row> right = rows(10, "R");
        SqlEngine engine = new SqlEngine();
        List<SqlEngine.Row> joined = engine.hashJoin(left, right,
                row -> new byte[]{row.key()[1]},
                row -> new byte[]{row.key()[1]});
        // 每个 left 的首字符在 0-9 中恰好匹配一个 right。
        assertThat(joined).hasSize(leftCount);
    }

    @Test
    void joinDifferentKeyColumns() {
        SqlEngine engine = new SqlEngine();
        List<SqlEngine.Row> left = List.of(
                new SqlEngine.Row(bytes("u1"), bytes("L1")),
                new SqlEngine.Row(bytes("u2"), bytes("L2")));
        List<SqlEngine.Row> right = List.of(
                new SqlEngine.Row(bytes("u1"), bytes("R1")),
                new SqlEngine.Row(bytes("u3"), bytes("R3")));
        assertThat(engine.hashJoin(left, right,
                row -> row.key(), row -> row.key())).hasSize(1);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1, 50})
    void aggregateSumAndAvg(int count) {
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new SqlEngine.Row(bytes("k" + i),
                    bytes(String.valueOf(i))));
        }
        SqlEngine engine = new SqlEngine();
        long sum = engine.aggregate(rows, AggregateType.SUM,
                Phase28QueryEdgeTest::value);
        long avg = engine.aggregate(rows, AggregateType.AVG,
                Phase28QueryEdgeTest::value);
        assertThat(sum).isEqualTo((long) count * (count - 1) / 2);
        assertThat(avg).isEqualTo((count - 1) / 2L);
    }

    @ParameterizedTest(name = "groups {0}")
    @ValueSource(ints = {2, 4})
    void groupBySumPerGroup(int groups) {
        SqlEngine engine = new SqlEngine();
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < groups * 3; i++) {
            rows.add(new SqlEngine.Row(
                    bytes("g" + (i % groups)),
                    bytes(String.valueOf(i + 1))));
        }
        Map<String, Long> sums = engine.groupBy(rows,
                row -> new byte[]{row.key()[1]}, AggregateType.SUM,
                Phase28QueryEdgeTest::value);
        assertThat(sums).hasSize(groups);
        assertThat(sums.values()).allMatch(sum -> sum > 0);
    }

    @Test
    void explainPlanNodeDetails() {
        ExplainPlan plan = new SqlEngine().explain(
                new SqlParser().parse("SELECT * FROM kv LIMIT 5"));
        assertThat(plan.nodes()).extracting(
                ExplainPlan.PlanNode::detail).contains("limit 5");
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
        String text = new String(row.value(), StandardCharsets.UTF_8)
                .replaceAll("\\D", "");
        return text.isEmpty() ? 0 : Long.parseLong(text);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
