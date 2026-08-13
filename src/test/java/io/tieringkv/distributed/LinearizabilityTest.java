package io.tieringkv.distributed;

import io.tieringkv.distributed.LinearizabilityChecker.Operation;
import io.tieringkv.distributed.LinearizabilityChecker.OpType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 线性一致性验证（ADR-0297）。 */
class LinearizabilityTest {

    private static final AtomicLong CLOCK = new AtomicLong();

    private static Operation op(OpType type, String value,
                                String result) {
        long invoke = CLOCK.incrementAndGet();
        long response = CLOCK.incrementAndGet();
        return new Operation(invoke, response, type, "k",
                value, result);
    }

    private static Operation op(OpType type, String value,
                                String result, long gap) {
        long invoke = CLOCK.incrementAndGet();
        long response = invoke + gap;
        return new Operation(invoke, response, type, "k",
                value, result);
    }

    @Test
    void emptyHistoryLinearizable() {
        assertThat(LinearizabilityChecker
                .isLinearizable(List.of())).isTrue();
    }

    @Test
    void singlePutHistory() {
        assertThat(LinearizabilityChecker.isLinearizable(
                List.of(op(OpType.PUT, "1", null)))).isTrue();
    }

    @Test
    void getAfterPutSeesValue() {
        List<Operation> history = List.of(
                op(OpType.PUT, "1", null),
                op(OpType.GET, null, "1"));
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isTrue();
    }

    @Test
    void getBeforePutSeesNull() {
        List<Operation> history = List.of(
                op(OpType.GET, null, null),
                op(OpType.PUT, "1", null));
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isTrue();
    }

    @Test
    void staleReadRejected() {
        List<Operation> history = List.of(
                op(OpType.PUT, "1", null),
                op(OpType.PUT, "2", null),
                op(OpType.GET, null, "1"));
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isFalse();
    }

    @Test
    void staleReadAfterResponseRejected() {
        List<Operation> history = List.of(
                op(OpType.PUT, "1", null),
                op(OpType.GET, null, "1"),
                op(OpType.PUT, "2", null));
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isTrue();
        List<Operation> violated = List.of(
                op(OpType.PUT, "1", null),
                op(OpType.PUT, "2", null),
                op(OpType.GET, null, "1", 100));
        assertThat(LinearizabilityChecker
                .isLinearizable(violated)).isFalse();
    }

    @Test
    void overlappingWritesEitherOrder() {
        Operation first = new Operation(1, 5, OpType.PUT, "k",
                "a", null);
        Operation second = new Operation(3, 7, OpType.PUT, "k",
                "b", null);
        Operation read = new Operation(8, 9, OpType.GET, "k",
                null, "b");
        assertThat(LinearizabilityChecker.isLinearizable(
                List.of(first, second, read))).isTrue();
    }

    @Test
    void realTimeOrderEnforced() {
        Operation put = new Operation(1, 2, OpType.PUT, "k",
                "a", null);
        Operation get = new Operation(3, 4, OpType.GET, "k",
                null, "b");
        assertThat(LinearizabilityChecker.isLinearizable(
                List.of(put, get))).isFalse();
    }

    @ParameterizedTest(name = "history {0}")
    @MethodSource("validHistories")
    void validHistoriesAccepted(String name,
                                List<Operation> history) {
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isTrue();
    }

    @ParameterizedTest(name = "violation {0}")
    @MethodSource("violatingHistories")
    void violatingHistoriesRejected(String name,
                                    List<Operation> history) {
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isFalse();
    }

    @ParameterizedTest(name = "concurrent history size {0}")
    @MethodSource("concurrentSizes")
    void concurrentGeneratedHistoriesLinearizable(int size) {
        List<Operation> history = new ArrayList<>();
        long[] times = new long[size * 2];
        for (int i = 0; i < times.length; i++) {
            times[i] = i + 1;
        }
        String value = "v";
        for (int i = 0; i < size; i++) {
            history.add(new Operation(times[i * 2],
                    times[i * 2 + 1], OpType.PUT, "k",
                    value + i, null));
        }
        history.add(new Operation(times[times.length - 1] + 1,
                times[times.length - 1] + 2, OpType.GET, "k",
                null, value + (size - 1)));
        assertThat(LinearizabilityChecker
                .isLinearizable(history)).isTrue();
    }

    static Stream<Arguments> validHistories() {
        return Stream.of(
                Arguments.of("empty", List.of()),
                Arguments.of("single-get", List.of(op(
                        OpType.GET, null, null))),
                Arguments.of("put-put-get", List.of(
                        op(OpType.PUT, "a", null),
                        op(OpType.PUT, "b", null),
                        op(OpType.GET, null, "b"))),
                Arguments.of("get-put-get", List.of(
                        op(OpType.GET, null, null),
                        op(OpType.PUT, "x", null),
                        op(OpType.GET, null, "x"))),
                Arguments.of("put-get-put-get", List.of(
                        op(OpType.PUT, "1", null),
                        op(OpType.GET, null, "1"),
                        op(OpType.PUT, "2", null),
                        op(OpType.GET, null, "2"))),
                Arguments.of("four-puts", List.of(
                        op(OpType.PUT, "1", null),
                        op(OpType.PUT, "2", null),
                        op(OpType.PUT, "3", null),
                        op(OpType.PUT, "4", null))));
    }

    static Stream<Arguments> violatingHistories() {
        return Stream.of(
                Arguments.of("stale-after-two", List.of(
                        op(OpType.PUT, "1", null),
                        op(OpType.PUT, "2", null),
                        op(OpType.GET, null, "1"))),
                Arguments.of("stale-after-three", List.of(
                        op(OpType.PUT, "1", null),
                        op(OpType.PUT, "2", null),
                        op(OpType.PUT, "3", null),
                        op(OpType.GET, null, "1"))),
                Arguments.of("impossible-read", List.of(
                        op(OpType.GET, null, "ghost"))),
                Arguments.of("middle-stale", List.of(
                        op(OpType.PUT, "1", null),
                        op(OpType.GET, null, "1"),
                        op(OpType.PUT, "2", null),
                        op(OpType.GET, null, "1"))));
    }

    static Stream<Arguments> concurrentSizes() {
        return Stream.of(2, 3, 4, 5, 6, 7)
                .map(Arguments::of);
    }
}
