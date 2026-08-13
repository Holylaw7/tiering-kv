package io.tieringkv.ci;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** GA 门禁终态 v17（ADR-0305）。 */
class GateConvergenceV17Test {

    @Test
    void registryNonEmpty() {
        assertThat(GateConvergenceV17.gates()).isNotEmpty();
    }

    @Test
    void hasSealedAndClosed() {
        assertThat(GateConvergenceV17.sealedCount()).isPositive();
        assertThat(GateConvergenceV17.closedCount()).isPositive();
    }

    @Test
    void summaryExportable() {
        assertThat(GateConvergenceV17.summary())
                .contains("GateConvergenceV17", "SEALED_GA");
    }

    @Test
    void noRollingDefer() {
        assertThat(GateConvergenceV17.gates()).allSatisfy(
                gate -> assertThat(gate.finalReason()).isNotBlank());
    }

    @ParameterizedTest(name = "gate {0}")
    @MethodSource("gateIds")
    void everyGateHasDisposition(String id) {
        assertThat(GateConvergenceV17.gates().stream()
                .filter(gate -> gate.id().equals(id))
                .findFirst().orElseThrow().disposition())
                .isNotNull();
    }

    static Stream<String> gateIds() {
        return GateConvergenceV17.gates().stream()
                .map(GateConvergenceV17.Gate::id);
    }
}
