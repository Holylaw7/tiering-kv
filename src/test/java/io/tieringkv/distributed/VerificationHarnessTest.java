package io.tieringkv.distributed;

import io.tieringkv.distributed.harness.VerificationHarness;
import io.tieringkv.distributed.harness.VerificationHarness.Report;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Jepsen 式 harness（ADR-0306）。 */
class VerificationHarnessTest {

    @Test
    void singleThreadLinearizable() throws Exception {
        Report report = new VerificationHarness(1, 50, "k").run();
        assertThat(report.linearizable()).isTrue();
        assertThat(report.operations()).isEqualTo(50);
    }

    @Test
    void multiThreadLinearizable() throws Exception {
        Report report = new VerificationHarness(4, 100, "k").run();
        assertThat(report.linearizable()).isTrue();
        assertThat(report.operations()).isEqualTo(400);
    }

    @Test
    void invalidArgsRejected() {
        assertThatThrownBy(() -> new VerificationHarness(0, 10,
                "k"));
    }

    private static void assertThatThrownBy(
            Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @ParameterizedTest(name = "threads={0} ops={1}")
    @MethodSource("matrix")
    void matrixLinearizable(int threads, int ops) throws Exception {
        Report report = new VerificationHarness(threads, ops,
                "k").run();
        assertThat(report.linearizable()).isTrue();
        assertThat(report.operations())
                .isEqualTo(threads * ops);
    }

    static Stream<Arguments> matrix() {
        return Stream.of(
                Arguments.of(1, 10),
                Arguments.of(2, 20),
                Arguments.of(3, 30),
                Arguments.of(4, 40),
                Arguments.of(5, 50),
                Arguments.of(8, 25));
    }
}
