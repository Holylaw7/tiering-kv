package io.tieringkv.ci;

import io.tieringkv.ci.GateConvergenceV12.Gate;
import io.tieringkv.ci.GateConvergenceV12.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实执行门禁收敛 v12（ADR-0234）：登记精确 + 禁止伪报。 */
class GateConvergenceV12Test {

    @Test
    void allExpectedGatesRegistered() {
        List<Gate> gates = GateConvergenceV12.gates();
        assertThat(gates).extracting(Gate::id).contains(
                "TD-048", "TD-049", "K8S-001", "REL-001",
                "BM-001", "BM-002", "TD-051", "TD-054",
                "TD-059", "TD-060", "TD-063", "TD-066",
                "TD-069", "TD-072", "TD-075", "TD-078",
                "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noDuplicateGateIds() {
        List<String> ids = GateConvergenceV12.gates().stream()
                .map(Gate::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyGateHasDescriptionBlockerAndElimination() {
        for (Gate gate : GateConvergenceV12.gates()) {
            assertThat(gate.description()).isNotBlank();
            assertThat(gate.expectedElimination()).isNotBlank();
            if (gate.status() != Status.GREEN_JVM) {
                assertThat(gate.blocker()).isNotBlank();
            }
        }
    }

    @Test
    void runnerGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV12.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RUNNER)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("runner"));
    }

    @Test
    void releaseGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV12.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RELEASE)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("tag"));
    }

    @Test
    void greenJvmGatesClosedThisPhase() {
        assertThat(GateConvergenceV12.gates()).filteredOn(
                gate -> gate.status() == Status.GREEN_JVM)
                .extracting(Gate::id)
                .containsExactlyInAnyOrder(
                        "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noFakeGreenReported() {
        for (Gate gate : GateConvergenceV12.gates()) {
            if (gate.status() == Status.GREEN_JVM) {
                assertThat(gate.id())
                        .isIn("TD-076", "TD-079", "TD-080");
            }
        }
    }

    @Test
    void unknownGateIdRejected() {
        assertThatThrownBy(() -> GateConvergenceV12.gate("TD-999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runnerGatesCarryPhase47Elimination() {
        assertThat(GateConvergenceV12.gates()).filteredOn(
                gate -> gate.status() != Status.GREEN_JVM)
                .allSatisfy(gate -> assertThat(
                        gate.expectedElimination())
                        .isEqualTo("Phase 47"));
    }

    @ParameterizedTest(name = "gate {0}")
    @CsvSource({
            "TD-048,requires Linux runner",
            "TD-049,requires Linux runner",
            "K8S-001,requires Linux runner",
            "REL-001,requires real tag trigger",
            "BM-001,requires cross-machine runner",
            "BM-002,requires cross-region runner",
            "TD-051,requires cross-region runner",
            "TD-054,requires cross-region runner",
            "TD-059,requires cross-region runner",
            "TD-060,requires cross-region runner",
            "TD-063,requires cross-region runner",
            "TD-066,requires Linux runner",
            "TD-069,requires Linux runner",
            "TD-072,requires Linux runner",
            "TD-075,requires real tag trigger",
            "TD-078,requires cross-machine runner",
            "TD-076,Phase 47",
            "TD-079,Phase 46",
            "TD-080,Phase 46"
    })
    void gateLookupAccurate(String id, String expectedBlocker) {
        Gate gate = GateConvergenceV12.gate(id);
        assertThat(gate.id()).isEqualTo(id);
        if (gate.status() == Status.GREEN_JVM) {
            assertThat(gate.expectedElimination())
                    .isEqualTo(expectedBlocker);
        } else {
            assertThat(gate.blocker())
                    .isEqualTo(expectedBlocker);
        }
    }

    @ParameterizedTest(name = "elimination {0}")
    @CsvSource({
            "TD-048,Phase 47",
            "TD-049,Phase 47",
            "K8S-001,Phase 47",
            "REL-001,Phase 47",
            "BM-001,Phase 47",
            "BM-002,Phase 47",
            "TD-051,Phase 47",
            "TD-054,Phase 47",
            "TD-059,Phase 47",
            "TD-060,Phase 47",
            "TD-063,Phase 47",
            "TD-066,Phase 47",
            "TD-069,Phase 47",
            "TD-072,Phase 47",
            "TD-075,Phase 47",
            "TD-078,Phase 47",
            "TD-076,Phase 47",
            "TD-079,Phase 46"
    })
    void eliminationPhaseAccurate(String id,
                                  String expectedPhase) {
        assertThat(GateConvergenceV12.gate(id)
                .expectedElimination())
                .isEqualTo(expectedPhase);
    }
}
