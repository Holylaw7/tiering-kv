package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive
        .Alert;
import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive
        .BaselineSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TiKV 回归归档（ADR-0260）：快照/趋势/告警/报表，口径如实。 */
class Phase49ProductionBaselineTest {

    @Test
    void snapshotRecordsAndLatest() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.2.0-rc1", 10, 20, 30, 12, 22,
                32, 150_000, 4096, 5, 10, 0, "LOCAL",
                "jvm measured");
        BaselineSnapshot latest = archive.latest();
        assertThat(latest.phase()).isEqualTo("v3.2.0-rc1");
        assertThat(latest.scope()).isEqualTo("LOCAL");
        assertThat(latest.getP99()).isEqualTo(30);
    }

    @Test
    void reportExportsCsvRows() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("p", 1, 2, 3, 1, 2, 3, 1000, 100,
                1, 2, 3, "LOCAL", "e");
        String report = archive.report();
        assertThat(report).contains("phase,scope,get_p50");
        assertThat(report).contains("p,LOCAL,1,2,3");
    }

    @Test
    void trendAccumulates() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addTrend("RTT", 120);
        archive.addTrend("RTO", 300);
        assertThat(archive.trends()).hasSize(2);
    }

    @Test
    void alertFiresAboveThreshold() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThat(archive.alertIf("P99", 5.0, 1.0)).isTrue();
        assertThat(archive.alerts()).hasSize(1);
        Alert alert = archive.alerts().get(0);
        assertThat(alert.value()).isEqualTo(5.0);
        assertThat(alert.threshold()).isEqualTo(1.0);
    }

    @Test
    void alertSilentWithinThreshold() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThat(archive.alertIf("P99", 0.5, 1.0)).isFalse();
        assertThat(archive.alerts()).isEmpty();
    }

    @Test
    void blankPhaseRejected() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThatThrownBy(() -> archive.addSnapshot("", 1, 2,
                3, 1, 2, 3, 1, 1, 1, 1, 1, "LOCAL", "e"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankScopeRejected() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThatThrownBy(() -> archive.addSnapshot("p", 1, 2,
                3, 1, 2, 3, 1, 1, 1, 1, 1, "", "e"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyReportMarksNoSnapshots() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThat(archive.report()).contains("no snapshots");
    }

    @ParameterizedTest(name = "phase={0} scope={1} throughput={2}")
    @MethodSource("snapshotMatrix")
    void snapshotMatrixRoundTrips(String phase, String scope,
                                  double throughput) {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot(phase, 10, 20, 30, 11, 21, 31,
                throughput, 2048, 5, 10, 0, scope, "evidence-"
                        + scope);
        assertThat(archive.latest().phase()).isEqualTo(phase);
        assertThat(archive.latest().scope()).isEqualTo(scope);
        assertThat(archive.report()).contains(scope);
    }

    @ParameterizedTest(name = "metric={0} value={1} threshold={2}")
    @CsvSource({
            "P99, 1.1, 1.0, true",
            "P99, 1.0, 1.0, false",
            "P95, 2.0, 1.5, true",
            "P95, 1.0, 1.5, false",
            "RTT, 200.0, 150.0, true",
            "RTT, 100.0, 150.0, false",
            "RTO, 300.0, 250.0, true",
            "RTO, 200.0, 250.0, false",
            "RPO, 5.0, 0.0, true",
            "RPO, 0.0, 0.0, false",
            "THROUGHPUT, 99.0, 100.0, false",
            "THROUGHPUT, 101.0, 100.0, true",
            "MEMORY, 4096.0, 2048.0, true",
            "MEMORY, 1024.0, 2048.0, false",
            "JITTER, 0.0, 10.0, false"
    })
    void alertMatrixFiresExactly(String metric, double value,
                                 double threshold,
                                 boolean expected) {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThat(archive.alertIf(metric, value, threshold))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "trend {0}")
    @MethodSource("trendMetrics")
    void trendMatrixRecords(String metric) {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addTrend(metric, 1.0);
        assertThat(archive.trends()).hasSize(1);
        assertThat(archive.trends().get(0).metric())
                .isEqualTo(metric);
    }

    @ParameterizedTest(name = "invalid {0}")
    @MethodSource("invalidCases")
    void invalidInputsRejected(String caseName) {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        assertThatThrownBy(() -> {
            switch (caseName) {
                case "blank-metric-alert" -> archive.alertIf("",
                        1, 1);
                case "blank-metric-trend" -> archive.addTrend("",
                        1);
                case "blank-evidence" -> archive.addSnapshot("p",
                        1, 2, 3, 1, 2, 3, 1, 1, 1, 1, 1, "LOCAL",
                        " ");
                default -> throw new IllegalArgumentException(
                        "unknown case");
            }
        }).isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> snapshotMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (String phase : new String[]{"v3.2.0-rc1", "v3.2.0",
                "phase49-local"}) {
            for (String scope : new String[]{"LOCAL",
                    "CROSS_MACHINE", "PENDING"}) {
                for (double throughput : new double[]{1000,
                        150_000}) {
                    builder.add(Arguments.of(phase, scope,
                            throughput));
                }
            }
        }
        return builder.build();
    }

    static Stream<Arguments> trendMetrics() {
        return Stream.of("RTT", "RTO", "RPO", "GET_P50",
                        "GET_P95", "GET_P99", "SET_P50", "SET_P95",
                        "SET_P99", "THROUGHPUT")
                .map(Arguments::of);
    }

    static Stream<Arguments> invalidCases() {
        return Stream.of("blank-metric-alert",
                        "blank-metric-trend", "blank-evidence")
                .map(Arguments::of);
    }
}
