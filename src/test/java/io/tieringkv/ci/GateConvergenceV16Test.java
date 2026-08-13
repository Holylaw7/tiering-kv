package io.tieringkv.ci;

import io.tieringkv.ci.GateConvergenceV16.Disposition;
import io.tieringkv.ci.GateConvergenceV16.Gate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 门禁最终处置 v16（ADR-0265）：唯一终态，取消滚动 defer。 */
class GateConvergenceV16Test {

    @Test
    void registryHasAllGates() {
        assertThat(GateConvergenceV16.gates()).hasSize(27);
    }

    @Test
    void closedCountMatches() {
        assertThat(GateConvergenceV16.closedCount())
                .isEqualTo(GateConvergenceV16.gates().stream()
                        .filter(gate -> gate.disposition()
                                == Disposition.CLOSED)
                        .count());
    }

    @Test
    void finalBlockedCountMatches() {
        assertThat(GateConvergenceV16.finalBlockedCount())
                .isEqualTo(GateConvergenceV16.gates().stream()
                        .filter(gate -> gate.disposition()
                                == Disposition.ENV_BLOCKED_FINAL)
                        .count());
    }

    @Test
    void registeredReleaseCountMatches() {
        assertThat(GateConvergenceV16.registeredReleaseCount())
                .isEqualTo(GateConvergenceV16.gates().stream()
                        .filter(gate -> gate.disposition()
                                == Disposition.REGISTERED_RELEASE)
                        .count());
    }

    @Test
    void everyGateHasSealedPhase() {
        assertThat(GateConvergenceV16.gates()).allSatisfy(
                gate -> assertThat(gate.sealedPhase()).isNotBlank());
    }

    @Test
    void noRollingDeferField() {
        assertThat(GateConvergenceV16.gates()).allSatisfy(
                gate -> assertThat(gate.finalReason()).isNotBlank());
    }

    @Test
    void summaryIsExportable() {
        String summary = GateConvergenceV16.summary();
        assertThat(summary).contains("GateConvergenceV16");
        assertThat(summary).contains("closed=");
        assertThat(summary).contains("envBlockedFinal=");
        assertThat(summary).contains("registeredRelease=");
    }

    @Test
    void unknownGateThrows() {
        assertThatThrownBy(() -> GateConvergenceV16.gate("TD-999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "gate {0} resolves")
    @MethodSource("allGateIds")
    void everyGateResolves(String gateId) {
        Gate gate = GateConvergenceV16.gate(gateId);
        assertThat(gate.description()).isNotBlank();
        assertThat(gate.disposition()).isNotNull();
        assertThat(gate.finalReason()).isNotBlank();
        assertThat(gate.sealedPhase()).isNotBlank();
    }

    @ParameterizedTest(name = "closed {0}")
    @MethodSource("closedGateIds")
    void closedGatesHaveClosedDisposition(String gateId) {
        assertThat(GateConvergenceV16.gate(gateId).disposition())
                .isEqualTo(Disposition.CLOSED);
    }

    @ParameterizedTest(name = "final blocked {0}")
    @MethodSource("finalBlockedGateIds")
    void finalBlockedGatesSealed(String gateId) {
        Gate gate = GateConvergenceV16.gate(gateId);
        assertThat(gate.disposition())
                .isEqualTo(Disposition.ENV_BLOCKED_FINAL);
        assertThat(gate.finalReason()).isNotBlank();
    }

    static Stream<String> allGateIds() {
        return GateConvergenceV16.gates().stream().map(Gate::id);
    }

    static Stream<String> closedGateIds() {
        return GateConvergenceV16.gates().stream()
                .filter(gate -> gate.disposition()
                        == Disposition.CLOSED)
                .map(Gate::id);
    }

    static Stream<String> finalBlockedGateIds() {
        return GateConvergenceV16.gates().stream()
                .filter(gate -> gate.disposition()
                        == Disposition.ENV_BLOCKED_FINAL)
                .map(Gate::id);
    }
}
