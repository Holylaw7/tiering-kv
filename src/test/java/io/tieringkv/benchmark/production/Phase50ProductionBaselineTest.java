package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.ci.GateConvergenceV16;
import io.tieringkv.ci.RunnerClosureArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 50 生产基线（LOCAL 口径 + 门禁终态封板）。 */
class Phase50ProductionBaselineTest {

    @Test
    void localSnapshotRecorded() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.2.0-ga", 8, 15, 25, 9, 16, 26,
                180_000, 2048, 3, 5, 0, "LOCAL",
                "jmh-backed local baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @Test
    void crossMachineMarkedPending() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.2.0-ga", 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, "PENDING",
                "awaits cross-machine runner");
        assertThat(archive.latest().scope()).isEqualTo("PENDING");
    }

    @Test
    void trendAndAlertAccumulate() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addTrend("RTT", 90);
        assertThat(archive.alertIf("P99", 1.2, 1.0)).isTrue();
        assertThat(archive.trends()).hasSize(1);
        assertThat(archive.alerts()).hasSize(1);
    }

    @Test
    void gateV16FinalDispositionsPresent() {
        assertThat(GateConvergenceV16.closedCount())
                .isGreaterThan(5);
        assertThat(GateConvergenceV16.finalBlockedCount())
                .isGreaterThan(10);
    }

    @Test
    void closureArchiveSealsGates() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        archive.record("TD-048", "Phase 50",
                GateConvergenceV16.Disposition.ENV_BLOCKED_FINAL
                        .name(),
                "sealed: no linux runner");
        assertThat(archive.size()).isEqualTo(1);
    }

    @Test
    void reportMarksNoRollover() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        String report = archive.report();
        assertThat(report).doesNotContain("next phase");
    }

    @ParameterizedTest(name = "scope {0} throughput {1}")
    @MethodSource("scopeMatrix")
    void scopeMatrixRoundTrips(String scope, double throughput) {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.2.0-ga", 8, 15, 25, 9, 16, 26,
                throughput, 2048, 3, 5, 0, scope, "evidence");
        assertThat(archive.latest().scope()).isEqualTo(scope);
        assertThat(archive.report()).contains(scope);
    }

    @ParameterizedTest(name = "disposition {0}")
    @MethodSource("dispositions")
    void gateDispositionCounts(String disposition) {
        switch (disposition) {
            case "CLOSED" ->
                    assertThat(GateConvergenceV16.closedCount())
                            .isPositive();
            case "ENV_BLOCKED_FINAL" ->
                    assertThat(GateConvergenceV16.finalBlockedCount())
                            .isPositive();
            case "REGISTERED_RELEASE" ->
                    assertThat(GateConvergenceV16
                            .registeredReleaseCount()).isPositive();
            default -> throw new AssertionError(disposition);
        }
    }

    @ParameterizedTest(name = "metric={0} value={1} threshold={2}")
    @CsvSource({
            "P99, 1.1, 1.0, true",
            "P99, 0.9, 1.0, false",
            "RTT, 100.0, 90.0, true",
            "RTT, 80.0, 90.0, false",
            "RPO, 1.0, 0.0, true",
            "RPO, 0.0, 0.0, false",
            "MEMORY, 4096.0, 2048.0, true",
            "MEMORY, 1024.0, 2048.0, false"
    })
    void alertMatrix(String metric, double value,
                     double threshold, boolean expected) {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThat(archive.alertIf(metric, value, threshold))
                .isEqualTo(expected);
    }

    static Stream<Arguments> scopeMatrix() {
        return Stream.of(
                Arguments.of("LOCAL", 150_000),
                Arguments.of("LOCAL", 180_000),
                Arguments.of("CROSS_MACHINE", 0),
                Arguments.of("CROSS_MACHINE", 120_000),
                Arguments.of("PENDING", 0),
                Arguments.of("PENDING", 0));
    }

    static Stream<Arguments> dispositions() {
        return Stream.of("CLOSED", "ENV_BLOCKED_FINAL",
                        "REGISTERED_RELEASE")
                .map(Arguments::of);
    }
}
