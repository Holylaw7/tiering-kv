package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.ci.GateConvergenceV17;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 56 生产基线（GA）。 */
class Phase56ProductionBaselineTest {

    @Test
    void baselineSnapshotLocal() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.7.0-ga", 8, 15, 25, 9, 16, 26,
                130_000, 2048, 3, 5, 0, "LOCAL", "ga baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @Test
    void gateV17Sealed() {
        assertThat(GateConvergenceV17.sealedCount()).isPositive();
    }

    @ParameterizedTest(name = "gate {0}")
    @MethodSource("gates")
    void gateDispositions(String id) {
        assertThat(GateConvergenceV17.gates().stream()
                .filter(gate -> gate.id().equals(id))
                .findFirst().orElseThrow().disposition())
                .isNotNull();
    }

    static Stream<Arguments> gates() {
        return Stream.of("TD-048", "REL-001", "TD-076",
                        "TD-089", "TD-090")
                .map(Arguments::of);
    }
}
