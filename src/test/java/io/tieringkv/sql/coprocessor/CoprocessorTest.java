package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Coprocessor 下推（ADR-0210）：范围 + 算子执行。 */
class CoprocessorTest {

    private final CoprocessorExecutor executor =
            new CoprocessorExecutor();

    @Test
    void filterWithinRangeAndThreshold() {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "a", "d", 50, List.of());
        List<Row> result = executor.execute(request, rows());
        assertThat(result).extracting(Row::key)
                .containsExactly("b", "c");
    }

    @Test
    void rangeExcludesEndKey() {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "a", "c", 0, List.of());
        List<Row> result = executor.execute(request, rows());
        assertThat(result).extracting(Row::key)
                .containsExactly("a", "b");
    }

    @Test
    void projectScalesValues() {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.PROJECT, "a", "d", 2, List.of());
        List<Row> result = executor.execute(request, rows());
        assertThat(result).extracting(Row::value)
                .containsExactly(20.0, 100.0, 140.0);
    }

    @Test
    void aggregateSumsRange() {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.AGGREGATE, "a", "d", 0, List.of());
        List<Row> result = executor.execute(request, rows());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("sum");
        assertThat(result.get(0).value()).isEqualTo(130);
    }

    @Test
    void aggregateEmptyZero() {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.AGGREGATE, "z", "zz", 0, List.of());
        List<Row> result = executor.execute(request, rows());
        assertThat(result.get(0).value()).isZero();
    }

    @Test
    void nullRequestRejected() {
        assertThatThrownBy(() -> executor.execute(null, rows()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRowsRejected() {
        assertThatThrownBy(() -> executor.execute(
                new CoprocessorRequest(Operator.FILTER, "a", "d",
                        0, List.of()), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRangeRejected() {
        assertThatThrownBy(() -> new CoprocessorRequest(
                Operator.FILTER, "", "d", 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sortedDeterministic() {
        List<Row> rows = List.of(
                new Row("c", 3), new Row("a", 1),
                new Row("b", 2));
        assertThat(executor.sorted(rows)).extracting(Row::key)
                .containsExactly("a", "b", "c");
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(doubles = {0, 50, 100, 200})
    void parameterizedThresholds(double threshold) {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "a", "d", threshold, List.of());
        List<Row> result = executor.execute(request, rows());
        assertThat(result).extracting(Row::value)
                .allMatch(value -> value >= threshold);
    }

    @ParameterizedTest(name = "range {0}")
    @ValueSource(strings = {"a", "b", "c"})
    void parameterizedRanges(String start) {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, start, "z", 0, List.of());
        assertThat(executor.execute(request, rows())).isNotEmpty();
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRowCounts(int count) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new Row("k" + i, i));
        }
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.AGGREGATE, "k0", "zz", 0, List.of());
        List<Row> result = executor.execute(request, rows);
        assertThat(result.get(0).value())
                .isEqualTo(count * (count - 1) / 2.0);
    }

    @Test
    void concurrentExecuteStable() throws Exception {
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "a", "d", 50, List.of());
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(executor.execute(request, rows()))
                            .hasSize(2);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    private static List<Row> rows() {
        return List.of(
                new Row("a", 10),
                new Row("b", 50),
                new Row("c", 70),
                new Row("d", 100));
    }
}
