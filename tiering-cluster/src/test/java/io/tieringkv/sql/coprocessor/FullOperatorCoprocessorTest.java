package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全算子下推（ADR-0222）：JOIN/GROUP_BY/ORDER_BY/LIMIT 链。 */
class FullOperatorCoprocessorTest {

    private final CoprocessorExecutor executor =
            new CoprocessorExecutor();

    @Test
    void joinEqualsLeftRightSum() {
        CompoundCoprocessorRequest request = request(
                List.of(Operator.JOIN), rows("a", "b"));
        List<Row> result = executor.executeCompound(request,
                rows("a", "b"));
        assertThat(result).extracting(Row::value)
                .containsExactly(0.0, 200.0);
    }

    @Test
    void groupBySumsPerKey() {
        CompoundCoprocessorRequest request = request(
                List.of(Operator.GROUP_BY), List.of());
        List<Row> result = executor.executeCompound(request,
                rows("a", "a", "b"));
        assertThat(result).extracting(Row::key)
                .containsExactlyInAnyOrder("a", "b");
        assertThat(result).filteredOn(row ->
                        row.key().equals("a"))
                .singleElement().extracting(Row::value)
                .isEqualTo(100.0);
    }

    @Test
    void orderByAscending() {
        CompoundCoprocessorRequest request = request(
                List.of(Operator.ORDER_BY), List.of());
        List<Row> result = executor.executeCompound(request,
                rows("b", "a", "c"));
        assertThat(result).extracting(Row::value)
                .containsExactly(0.0, 100.0, 200.0);
    }

    @Test
    void orderByDescending() {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.ORDER_BY),
                        "a", "z", 0, List.of(),
                        Integer.MAX_VALUE, true);
        List<Row> result = executor.executeCompound(request,
                rows("b", "a", "c"));
        assertThat(result).extracting(Row::value)
                .containsExactly(200.0, 100.0, 0.0);
    }

    @Test
    void limitTruncates() {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.LIMIT),
                        "a", "z", 0, List.of(), 2, false);
        List<Row> result = executor.executeCompound(request,
                rows("a", "b", "c"));
        assertThat(result).hasSize(2);
    }

    @Test
    void fixedChainOrderJoinFilterProject() {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.PROJECT, Operator.FILTER,
                                Operator.JOIN),
                        "a", "z", 50,
                        rows("a", "b"), Integer.MAX_VALUE, false);
        // 固定顺序 JOIN → FILTER → PROJECT
        List<Row> result = executor.executeCompound(request,
                rows("a", "b"));
        // JOIN: a=0+0, b=100+100 → FILTER >=50 → b=200
        // → PROJECT ×50 → 10000
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo(10_000.0);
    }

    @Test
    void fixedChainGroupByOrderByLimit() {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.ORDER_BY, Operator.LIMIT,
                                Operator.GROUP_BY),
                        "a", "z", 0, List.of(), 1, false);
        List<Row> result = executor.executeCompound(request,
                rows("a", "a", "b"));
        // GROUP_BY → a=100, b=200 → ORDER_BY asc → a,b → LIMIT 1 → a
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("a");
    }

    @Test
    void chainConsistentWithUpperSql() {
        List<Row> data = rows("a", "a", "b");
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER,
                                Operator.GROUP_BY),
                        "a", "z", 50, List.of(),
                        Integer.MAX_VALUE, false);
        List<Row> result = executor.executeCompound(request,
                data);
        // 上层 SQL 语义：FILTER(value>=50) 后按 key 分组求和
        java.util.Map<String, Double> expected =
                new java.util.LinkedHashMap<>();
        for (Row row : data) {
            if (row.value() >= 50) {
                expected.merge(row.key(), row.value(),
                        Double::sum);
            }
        }
        assertThat(result).hasSize(expected.size());
        for (Row row : result) {
            assertThat(row.value())
                    .isEqualTo(expected.get(row.key()));
        }
    }

    @Test
    void limitZeroReturnsEmpty() {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.LIMIT),
                        "a", "z", 0, List.of(), 0, false);
        assertThat(executor.executeCompound(request,
                rows("a", "b"))).isEmpty();
    }

    @Test
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(Operator.JOIN), "a", "z", 0,
                null, 1, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(Operator.LIMIT), "a", "z", 0,
                List.of(), -1, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "left={0} right={1}")
    @CsvSource({
            "1,1,1",
            "1,2,1",
            "2,1,1",
            "2,2,2",
            "3,3,3",
            "3,5,3",
            "5,3,3",
            "5,5,5",
            "1,5,1",
            "5,1,1",
            "2,5,2",
            "5,2,2",
            "4,4,4",
            "4,6,4",
            "6,4,4",
            "6,6,6",
            "1,10,1",
            "10,1,1",
            "10,10,10",
            "10,20,10",
            "20,10,10",
            "20,20,20",
            "3,10,3",
            "10,3,3",
            "7,7,7",
            "8,9,8",
            "9,8,8",
            "15,15,15",
            "15,30,15",
            "30,15,15"
    })
    void parameterizedJoinMatrix(int left, int right,
                                 int expectedRows) {
        CompoundCoprocessorRequest request = request(
                List.of(Operator.JOIN), rowsOf(left));
        List<Row> result = executor.executeCompound(request,
                rowsOf(right));
        assertThat(result).hasSize(expectedRows);
        if (expectedRows > 0) {
            Row first = result.get(0);
            assertThat(first.value())
                    .isEqualTo(first.key().equals("k0")
                            ? 0.0 : -1.0);
        }
    }

    @ParameterizedTest(name = "groups={0} per={1}")
    @CsvSource({
            "1,1,1",
            "1,10,1",
            "2,1,2",
            "2,10,2",
            "3,1,3",
            "3,10,3",
            "4,5,4",
            "5,5,5",
            "10,10,10",
            "10,1,10",
            "1,100,1",
            "2,50,2",
            "3,20,3",
            "5,10,5",
            "7,3,7",
            "8,4,8",
            "9,2,9",
            "10,2,10",
            "6,6,6",
            "12,3,12"
    })
    void parameterizedGroupByMatrix(int groups, int perGroup,
                                    int expectedGroups) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < groups * perGroup; i++) {
            data.add(new Row("g" + (i % groups), i * 10.0));
        }
        CompoundCoprocessorRequest request = request(
                List.of(Operator.GROUP_BY), List.of());
        List<Row> result = executor.executeCompound(request,
                data);
        assertThat(result).hasSize(expectedGroups);
    }

    @ParameterizedTest(name = "rows={0} limit={1} desc={2}")
    @CsvSource({
            "1,1,false",
            "2,1,false",
            "3,2,false",
            "5,3,false",
            "10,5,false",
            "10,10,false",
            "20,10,false",
            "20,20,false",
            "50,10,false",
            "50,50,false",
            "3,1,true",
            "5,2,true",
            "10,3,true",
            "20,5,true",
            "50,10,true"
    })
    void parameterizedOrderLimitMatrix(int rows, int limit,
                                       boolean descending) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, i * 10.0));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.ORDER_BY,
                                Operator.LIMIT),
                        "k0", "zz", 0, List.of(), limit,
                        descending);
        List<Row> result = executor.executeCompound(request,
                data);
        assertThat(result).hasSize(Math.min(rows, limit));
        if (!result.isEmpty()) {
            double first = result.get(0).value();
            if (descending) {
                assertThat(first).isEqualTo((rows - 1) * 10.0);
            } else {
                assertThat(first).isZero();
            }
        }
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {0, 1, 3, 10, Integer.MAX_VALUE})
    void parameterizedLimitValues(int limit) {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.LIMIT),
                        "a", "z", 0, List.of(), limit, false);
        List<Row> result = executor.executeCompound(request,
                rows("a", "b", "c"));
        assertThat(result)
                .hasSize(Math.min(3, limit));
    }

    private static CompoundCoprocessorRequest request(
            List<Operator> operators, List<Row> joinRows) {
        return new CompoundCoprocessorRequest(operators,
                "a", "z", 0, joinRows, Integer.MAX_VALUE,
                false);
    }

    private static List<Row> rowsOf(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row("k" + i, i * 100.0));
        }
        return rows;
    }

    private static List<Row> rows(String... keys) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < keys.length; i++) {
            rows.add(new Row(keys[i], i * 100.0));
        }
        return rows;
    }
}
