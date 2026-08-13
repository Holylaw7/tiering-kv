package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest
        .WindowFunction;
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

/** 多表 JOIN / 窗口函数下推（ADR-0229）：N 表连接 + ROW_NUMBER/RANK。 */
class MultiTableJoinWindowTest {

    private final CoprocessorExecutor executor =
            new CoprocessorExecutor();

    @Test
    void threeWayJoin() {
        CompoundCoprocessorRequest request = multiRequest(
                List.of(Operator.JOIN), 4, List.of(4), 4);
        List<Row> result = executor.executeCompound(request,
                rowsOf(4));
        assertThat(result).hasSize(4);
        assertThat(result.get(0).value()).isEqualTo(0.0);
    }

    @Test
    void fourWayJoin() {
        CompoundCoprocessorRequest request = multiRequest(
                List.of(Operator.JOIN), 5,
                List.of(5, 5), 5);
        List<Row> result = executor.executeCompound(request,
                rowsOf(5));
        assertThat(result).hasSize(5);
    }

    @Test
    void joinTablesEmptyEquivalentToSingleJoin() {
        CompoundCoprocessorRequest single = request(
                List.of(Operator.JOIN), rowsOf(3), List.of());
        CompoundCoprocessorRequest multi = multiRequest(
                List.of(Operator.JOIN), 3, List.of(), 3);
        List<Row> a = executor.executeCompound(single, rowsOf(3));
        List<Row> b = executor.executeCompound(multi, rowsOf(3));
        assertThat(b).hasSize(a.size());
    }

    @Test
    void rowNumberWindow() {
        CompoundCoprocessorRequest request = windowRequest(
                WindowFunction.ROW_NUMBER);
        List<Row> result = executor.executeCompound(request,
                rows("a", "a", "b", "c", "c", "c"));
        assertThat(result).hasSize(6);
        assertThat(result).filteredOn(row ->
                        row.key().equals("a"))
                .extracting(Row::value)
                .containsExactly(1.0, 2.0);
        assertThat(result).filteredOn(row ->
                        row.key().equals("c"))
                .extracting(Row::value)
                .containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void rankWindow() {
        CompoundCoprocessorRequest request = windowRequest(
                WindowFunction.RANK);
        List<Row> result = executor.executeCompound(request,
                List.of(new Row("a", 10), new Row("a", 10),
                        new Row("a", 20), new Row("b", 5)));
        assertThat(result).hasSize(4);
        assertThat(result).filteredOn(row ->
                        row.key().equals("a"))
                .extracting(Row::value)
                .containsExactly(1.0, 1.0, 2.0);
    }

    @Test
    void windowNoneIdentity() {
        CompoundCoprocessorRequest request = windowRequest(
                WindowFunction.NONE);
        List<Row> result = executor.executeCompound(request,
                rows("a", "b"));
        assertThat(result).hasSize(2);
    }

    @Test
    void windowConsistentWithUpperSql() {
        CompoundCoprocessorRequest request = windowRequest(
                WindowFunction.ROW_NUMBER);
        List<Row> data = rows("a", "a", "a", "b");
        List<Row> result = executor.executeCompound(request,
                data);
        long aCount = data.stream()
                .filter(row -> row.key().equals("a")).count();
        assertThat(result).filteredOn(row ->
                        row.key().equals("a"))
                .hasSize((int) aCount);
        assertThat(result).filteredOn(row ->
                        row.key().equals("a"))
                .extracting(Row::value)
                .containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void invalidJoinTablesRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(Operator.JOIN), "a", "z", 0,
                List.of(), null, 1, false,
                WindowFunction.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiJoinThenWindowChain() {
        CompoundCoprocessorRequest request = new
                CompoundCoprocessorRequest(
                List.of(Operator.JOIN, Operator.WINDOW,
                        Operator.ORDER_BY),
                "k0", "zz", 0, rowsOf(3),
                List.of(rowsOf(3)), Integer.MAX_VALUE,
                false, WindowFunction.ROW_NUMBER);
        List<Row> result = executor.executeCompound(request,
                rowsOf(3));
        assertThat(result).hasSize(3);
        assertThat(result).extracting(Row::value)
                .containsExactly(1.0, 1.0, 1.0);
    }

    @Test
    void multiJoinThenGroupBy() {
        CompoundCoprocessorRequest request = new
                CompoundCoprocessorRequest(
                List.of(Operator.JOIN, Operator.GROUP_BY),
                "k0", "zz", 0, rowsOf(2),
                List.of(rowsOf(2)), Integer.MAX_VALUE,
                false, WindowFunction.NONE);
        List<Row> result = executor.executeCompound(request,
                rowsOf(2));
        assertThat(result).hasSize(2);
    }

    @ParameterizedTest(name = "main={0} t1={1} t2={2} t3={3}")
    @CsvSource({
            "1,1,0,0,1",
            "1,2,0,0,1",
            "2,1,0,0,1",
            "2,2,0,0,2",
            "3,3,0,0,3",
            "3,5,0,0,3",
            "5,3,0,0,3",
            "5,5,0,0,5",
            "4,4,4,0,4",
            "4,6,5,0,4",
            "6,4,5,0,4",
            "6,6,6,0,6",
            "3,3,3,3,3",
            "3,5,5,5,3",
            "5,3,5,5,3",
            "5,5,3,5,3",
            "5,5,5,3,3",
            "10,10,10,0,10",
            "10,20,10,0,10",
            "20,10,10,0,10",
            "20,20,20,0,20",
            "7,7,7,7,7",
            "8,9,10,0,8",
            "9,8,7,0,7",
            "10,8,6,0,6",
            "12,12,12,12,12",
            "15,10,10,10,10",
            "15,15,10,10,10",
            "15,15,15,10,10",
            "15,15,15,15,15",
            "1,1,1,1,1",
            "2,2,2,2,2",
            "4,4,4,4,4",
            "8,8,8,8,8",
            "16,16,16,16,16"
    })
    void parameterizedMultiJoinMatrix(int main, int t1, int t2,
                                      int t3, int expected) {
        List<Integer> tables = new ArrayList<>();
        if (t1 > 0) {
            tables.add(t1);
        }
        if (t2 > 0) {
            tables.add(t2);
        }
        if (t3 > 0) {
            tables.add(t3);
        }
        List<List<Row>> joinTables = new ArrayList<>();
        for (int size : tables) {
            joinTables.add(rowsOf(size));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.JOIN), "k0", "zz", 0,
                        rowsOf(main), joinTables,
                        Integer.MAX_VALUE, false,
                        WindowFunction.NONE);
        List<Row> result = executor.executeCompound(request,
                rowsOf(main));
        assertThat(result).hasSize(expected);
    }

    @ParameterizedTest(name = "parts={0} per={1} dups={2}")
    @CsvSource({
            "1,1,0,ROW_NUMBER,1",
            "1,5,0,ROW_NUMBER,5",
            "2,3,0,ROW_NUMBER,3",
            "3,4,0,ROW_NUMBER,4",
            "5,5,0,ROW_NUMBER,5",
            "1,5,1,RANK,5",
            "1,5,2,RANK,4",
            "2,4,1,RANK,4",
            "3,3,1,RANK,3",
            "4,5,2,RANK,4",
            "5,4,1,RANK,4",
            "2,2,1,RANK,2",
            "3,5,3,RANK,3",
            "4,4,2,RANK,3",
            "5,3,1,RANK,3",
            "6,4,1,RANK,4",
            "7,3,2,RANK,2",
            "8,5,3,RANK,3",
            "9,4,0,ROW_NUMBER,4",
            "10,3,0,ROW_NUMBER,3"
    })
    void parameterizedWindowMatrix(int partitions, int perPartition,
                                   int duplicates,
                                   String function,
                                   int expectedMax) {
        List<Row> data = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            for (int i = 0; i < perPartition; i++) {
                data.add(new Row("p" + p,
                        i < duplicates ? 0 : i * 10.0));
            }
        }
        CompoundCoprocessorRequest request = windowRequest(
                WindowFunction.valueOf(function));
        List<Row> result = executor.executeCompound(request,
                data);
        assertThat(result).hasSize(data.size());
        double max = result.stream()
                .mapToDouble(Row::value).max().orElse(0);
        assertThat(max).isEqualTo(expectedMax);
    }

    @ParameterizedTest(name = "tables {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void parameterizedTableCounts(int tables) {
        List<List<Row>> joinTables = new ArrayList<>();
        for (int i = 1; i < tables; i++) {
            joinTables.add(rowsOf(10));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.JOIN), "k0", "zz", 0,
                        rowsOf(10), joinTables,
                        Integer.MAX_VALUE, false,
                        WindowFunction.NONE);
        assertThat(executor.executeCompound(request, rowsOf(10)))
                .hasSize(10);
    }

    private static CompoundCoprocessorRequest multiRequest(
            List<Operator> operators, int main,
            List<Integer> tableSizes, int limit) {
        List<List<Row>> joinTables = new ArrayList<>();
        for (int size : tableSizes) {
            joinTables.add(rowsOf(size));
        }
        return new CompoundCoprocessorRequest(operators,
                "k0", "zz", 0, rowsOf(main), joinTables,
                limit, false, WindowFunction.NONE);
    }

    private static CompoundCoprocessorRequest request(
            List<Operator> operators, List<Row> joinRows,
            List<List<Row>> joinTables) {
        return new CompoundCoprocessorRequest(operators,
                "a", "z", 0, joinRows, joinTables,
                Integer.MAX_VALUE, false, WindowFunction.NONE);
    }

    private static CompoundCoprocessorRequest windowRequest(
            WindowFunction function) {
        return new CompoundCoprocessorRequest(
                List.of(Operator.WINDOW), "a", "z", 0,
                List.of(), List.of(), Integer.MAX_VALUE,
                false, function);
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
            rows.add(new Row(keys[i], i * 10.0));
        }
        return rows;
    }
}
