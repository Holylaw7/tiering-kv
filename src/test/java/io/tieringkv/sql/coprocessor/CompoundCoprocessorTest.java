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

/** 多算子联合下推（ADR-0215）：FILTER → PROJECT → AGGREGATE。 */
class CompoundCoprocessorTest {

    private final CoprocessorExecutor executor =
            new CoprocessorExecutor();

    @Test
    void filterThenAggregate() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.AGGREGATE),
                "a", "d", 50);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo(120);
    }

    @Test
    void filterThenProject() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.PROJECT),
                "a", "d", 50);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).extracting(Row::value)
                .containsExactly(2500.0, 3500.0);
    }

    @Test
    void fullChain() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.PROJECT,
                        Operator.AGGREGATE),
                "a", "d", 50);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo(6000);
    }

    @Test
    void emptyOperatorsRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(), "a", "d", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRequestRejected() {
        assertThatThrownBy(() -> executor.executeCompound(
                null, rows()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRowsRejected() {
        assertThatThrownBy(() -> executor.executeCompound(
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER), "a", "d", 0),
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRangeRejected() {
        assertThatThrownBy(() -> new CompoundCoprocessorRequest(
                List.of(Operator.FILTER), "", "d", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chainResultConsistentWithUpperSql() {
        // FILTER(>=50) → sum = 50+70 = 120
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.AGGREGATE),
                "a", "d", 50);
        List<Row> result = executor.executeCompound(request, rows());
        double sum = rows().stream()
                .filter(r -> r.key().compareTo("a") >= 0
                        && r.key().compareTo("d") < 0)
                .filter(r -> r.value() >= 50)
                .mapToDouble(Row::value).sum();
        assertThat(result.get(0).value()).isEqualTo(sum);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(doubles = {0, 50, 100, 150})
    void parameterizedThresholds(double threshold) {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.AGGREGATE),
                "a", "d", threshold);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).hasSize(1);
    }

    @ParameterizedTest(name = "range {0}")
    @ValueSource(strings = {"a", "b", "c", "d", "e"})
    void parameterizedRanges(String start) {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.AGGREGATE),
                start, "z", 0);
        assertThat(executor.executeCompound(request, rows()))
                .hasSize(1);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRowCounts(int count) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            data.add(new Row("k" + i, i));
        }
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.AGGREGATE),
                "k0", "zz", 0);
        List<Row> result = executor.executeCompound(request, data);
        assertThat(result.get(0).value())
                .isEqualTo(count * (count - 1) / 2.0);
    }

    @Test
    void concurrentChainStable() throws Exception {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.AGGREGATE),
                "a", "d", 50);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    assertThat(executor.executeCompound(request,
                            rows()).get(0).value()).isEqualTo(120);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @Test
    void chainPreservesOrder() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER, Operator.PROJECT,
                        Operator.FILTER),
                "a", "d", 50);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).hasSize(2);
    }

    @Test
    void rangeEndExclusive() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.FILTER), "a", "d", 0);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).extracting(Row::key)
                .containsExactly("a", "b", "c");
    }

    @Test
    void emptyRowsAggregateReturnsZero() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.AGGREGATE), "a", "z", 0);
        List<Row> result = executor.executeCompound(request,
                List.of());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isZero();
    }

    @Test
    void projectTwiceScalesValues() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.PROJECT, Operator.PROJECT),
                "a", "d", 10);
        List<Row> result = executor.executeCompound(request, rows());
        assertThat(result).extracting(Row::value)
                .containsExactly(1000.0, 5000.0, 7000.0);
    }

    @Test
    void filterAfterProjectMatchesUpperSql() {
        CompoundCoprocessorRequest request = new CompoundCoprocessorRequest(
                List.of(Operator.PROJECT, Operator.FILTER),
                "a", "d", 2500);
        List<Row> result = executor.executeCompound(request, rows());
        double expected = rows().stream()
                .filter(r -> r.key().compareTo("a") >= 0
                        && r.key().compareTo("d") < 0)
                .mapToDouble(r -> r.value() * 2500)
                .filter(v -> v >= 2500)
                .count();
        assertThat(result).hasSize((int) expected);
    }

    @ParameterizedTest(name = "op1={0} op2={1} threshold={2} count={3}")
    @CsvSource({
            "FILTER,FILTER,10,5",
            "FILTER,PROJECT,10,5",
            "FILTER,AGGREGATE,10,5",
            "PROJECT,FILTER,10,5",
            "PROJECT,PROJECT,10,5",
            "PROJECT,AGGREGATE,10,5",
            "AGGREGATE,FILTER,10,5",
            "AGGREGATE,PROJECT,10,5",
            "AGGREGATE,AGGREGATE,10,5",
            "FILTER,FILTER,50,10",
            "FILTER,PROJECT,50,10",
            "FILTER,AGGREGATE,50,10",
            "PROJECT,FILTER,50,10",
            "PROJECT,PROJECT,50,10",
            "PROJECT,AGGREGATE,50,10",
            "AGGREGATE,FILTER,50,10",
            "AGGREGATE,PROJECT,50,10",
            "AGGREGATE,AGGREGATE,50,10",
            "FILTER,FILTER,100,20",
            "FILTER,PROJECT,100,20",
            "FILTER,AGGREGATE,100,20",
            "PROJECT,FILTER,100,20",
            "PROJECT,PROJECT,100,20",
            "PROJECT,AGGREGATE,100,20",
            "AGGREGATE,FILTER,100,20",
            "AGGREGATE,PROJECT,100,20",
            "AGGREGATE,AGGREGATE,100,20",
            "FILTER,FILTER,500,50",
            "PROJECT,PROJECT,500,50",
            "AGGREGATE,AGGREGATE,500,50"
    })
    void parameterizedTwoOpChains(String op1, String op2,
                                  double threshold, int count) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            data.add(new Row("k" + i, i * 10.0));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.valueOf(op1),
                                Operator.valueOf(op2)),
                        "k0", "zz", threshold);
        List<Row> result = executor.executeCompound(request, data);
        assertThat(result).isNotNull();
        if (op1.equals("AGGREGATE")
                || op2.equals("AGGREGATE")) {
            assertThat(result).hasSize(1);
        } else {
            assertThat(result.size()).isLessThanOrEqualTo(count);
        }
    }

    @ParameterizedTest(name = "threshold={0} count={1}")
    @CsvSource({
            "0,5",
            "10,5",
            "20,5",
            "50,5",
            "100,5",
            "0,10",
            "50,10",
            "100,10",
            "150,10",
            "0,20",
            "50,20",
            "100,20",
            "200,20",
            "500,50",
            "1000,100"
    })
    void parameterizedFilterEquivalence(double threshold,
                                        int count) {
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            data.add(new Row("k" + i, i * 10.0));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER), "k0", "zz",
                        threshold);
        List<Row> result = executor.executeCompound(request, data);
        long expected = data.stream()
                .filter(row -> row.value() >= threshold)
                .count();
        assertThat(result).hasSize((int) expected);
    }

    @ParameterizedTest(name = "chain length {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void parameterizedChainLengths(int length) {
        List<Operator> operators = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            operators.add(i == length - 1
                    ? Operator.AGGREGATE
                    : i % 2 == 0 ? Operator.FILTER
                    : Operator.PROJECT);
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(operators,
                        "a", "d", 50);
        List<Row> result = executor.executeCompound(request,
                rows());
        assertThat(result).hasSize(1);
    }

    private static List<Row> rows() {
        return List.of(
                new Row("a", 10),
                new Row("b", 50),
                new Row("c", 70),
                new Row("d", 100));
    }
}
