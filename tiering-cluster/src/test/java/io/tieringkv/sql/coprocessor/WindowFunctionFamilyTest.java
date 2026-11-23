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

/** 窗口函数全族（ADR-0236）：LAG/LEAD/SUM/COUNT/AVG OVER。 */
class WindowFunctionFamilyTest {

    private final CoprocessorExecutor executor =
            new CoprocessorExecutor();

    @Test
    void lagOffsetsPreviousValue() {
        List<Row> result = run(WindowFunction.LAG, rows(3));
        assertThat(result).extracting(Row::value)
                .containsExactly(0.0, 0.0, 10.0);
    }

    @Test
    void leadOffsetsNextValue() {
        List<Row> result = run(WindowFunction.LEAD, rows(3));
        assertThat(result).extracting(Row::value)
                .containsExactly(10.0, 20.0, 0.0);
    }

    @Test
    void sumOverPrefixSum() {
        List<Row> result = run(WindowFunction.SUM_OVER,
                rows(3));
        assertThat(result).extracting(Row::value)
                .containsExactly(0.0, 10.0, 30.0);
    }

    @Test
    void countOverPrefixCount() {
        List<Row> result = run(WindowFunction.COUNT_OVER,
                rows(3));
        assertThat(result).extracting(Row::value)
                .containsExactly(1.0, 2.0, 3.0);
    }

    @Test
    void avgOverPrefixAverage() {
        List<Row> result = run(WindowFunction.AVG_OVER,
                rows(3));
        assertThat(result).extracting(Row::value)
                .containsExactly(0.0, 5.0, 10.0);
    }

    @Test
    void lagFirstRowZero() {
        List<Row> result = run(WindowFunction.LAG,
                List.of(new Row("a", 10)));
        assertThat(result.get(0).value()).isZero();
    }

    @Test
    void leadLastRowZero() {
        List<Row> result = run(WindowFunction.LEAD,
                List.of(new Row("a", 10)));
        assertThat(result.get(0).value()).isZero();
    }

    @Test
    void familyConsistentWithUpperSql() {
        List<Row> data = rows(4);
        List<Row> sum = run(WindowFunction.SUM_OVER, data);
        List<Row> count = run(WindowFunction.COUNT_OVER, data);
        List<Row> avg = run(WindowFunction.AVG_OVER, data);
        assertThat(avg).hasSize(data.size());
        for (int i = 0; i < avg.size(); i++) {
            double expected = sum.get(i).value()
                    / count.get(i).value();
            assertThat(avg.get(i).value())
                    .isEqualTo(expected);
        }
    }

    @Test
    void invalidFunctionRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(Operator.WINDOW), "a", "z", 0,
                List.of(), List.of(), Integer.MAX_VALUE,
                false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chainWithWindowFamily() {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER,
                                Operator.WINDOW),
                        "a", "z", 20, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        WindowFunction.SUM_OVER);
        List<Row> result = executor.executeCompound(request,
                rows(3));
        assertThat(result).extracting(Row::value)
                .containsExactly(20.0);
    }

    @ParameterizedTest(name = "fn={0} per={1} last={2}")
    @CsvSource({
            "LAG,1,0",
            "LAG,2,0",
            "LAG,3,10",
            "LAG,4,20",
            "LAG,5,30",
            "LAG,6,40",
            "LAG,7,50",
            "LEAD,1,0",
            "LEAD,2,0",
            "LEAD,3,0",
            "LEAD,4,0",
            "LEAD,5,0",
            "LEAD,6,0",
            "LEAD,7,0",
            "SUM_OVER,1,0",
            "SUM_OVER,2,10",
            "SUM_OVER,3,30",
            "SUM_OVER,4,60",
            "SUM_OVER,5,100",
            "SUM_OVER,6,150",
            "SUM_OVER,7,210",
            "COUNT_OVER,1,1",
            "COUNT_OVER,2,2",
            "COUNT_OVER,3,3",
            "COUNT_OVER,4,4",
            "COUNT_OVER,5,5",
            "COUNT_OVER,6,6",
            "COUNT_OVER,7,7",
            "AVG_OVER,1,0",
            "AVG_OVER,2,5",
            "AVG_OVER,3,10",
            "AVG_OVER,4,15",
            "AVG_OVER,5,20",
            "AVG_OVER,6,25",
            "AVG_OVER,7,30"
    })
    void parameterizedWindowValues(String function, int per,
                                   double expectedLast) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < per; i++) {
            data.add(new Row("a", i * 10.0));
        }
        List<Row> result = run(
                WindowFunction.valueOf(function), data);
        assertThat(result).hasSize(per);
        assertThat(result.get(per - 1).value())
                .isEqualTo(expectedLast);
    }

    @ParameterizedTest(name = "parts={0} per={1} fn={2}")
    @CsvSource({
            "1,3,LAG,3",
            "2,3,LAG,6",
            "3,3,LAG,9",
            "1,5,LEAD,5",
            "2,5,LEAD,10",
            "3,5,LEAD,15",
            "1,4,SUM_OVER,4",
            "2,4,SUM_OVER,8",
            "3,4,SUM_OVER,12",
            "1,6,COUNT_OVER,6",
            "2,6,COUNT_OVER,12",
            "3,6,COUNT_OVER,18",
            "1,7,AVG_OVER,7",
            "2,7,AVG_OVER,14",
            "3,7,AVG_OVER,21",
            "4,3,SUM_OVER,12",
            "5,3,COUNT_OVER,15",
            "6,3,AVG_OVER,18",
            "7,3,LAG,21",
            "8,3,LEAD,24"
    })
    void parameterizedPartitionMatrix(int partitions, int per,
                                      String function,
                                      int expectedRows) {
        List<Row> data = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            for (int i = 0; i < per; i++) {
                data.add(new Row("p" + p, i * 10.0));
            }
        }
        List<Row> result = run(
                WindowFunction.valueOf(function), data);
        assertThat(result).hasSize(expectedRows);
    }

    @ParameterizedTest(name = "per {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void parameterizedPartitionSizes(int per) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < per; i++) {
            data.add(new Row("a", i * 10.0));
        }
        List<Row> result = run(WindowFunction.SUM_OVER, data);
        assertThat(result).hasSize(per);
        assertThat(result.get(per - 1).value())
                .isEqualTo(per * (per - 1) / 2.0 * 10);
    }

    private static List<Row> run(WindowFunction function,
                                 List<Row> data) {
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW), "a", "z", 0,
                        List.of(), List.of(), Integer.MAX_VALUE,
                        false, function);
        return new CoprocessorExecutor().executeCompound(
                request, data);
    }

    private static List<Row> rows(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row("a", i * 10.0));
        }
        return rows;
    }
}
