package io.tieringkv.ci;

import io.tieringkv.ci.GateConvergenceV9.Gate;
import io.tieringkv.ci.GateConvergenceV9.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实执行门禁收敛 v9（ADR-0213）：登记精确 + 禁止伪报完成。 */
class GateConvergenceV9Test {

    @Test
    void allExpectedGatesRegistered() {
        List<Gate> gates = GateConvergenceV9.gates();
        assertThat(gates).extracting(Gate::id).contains(
                "TD-048", "TD-049", "K8S-001", "REL-001",
                "BM-001", "BM-002", "TD-051", "TD-054",
                "TD-059", "TD-060", "TD-063", "TD-066",
                "TD-069", "TD-072", "TD-075", "TD-078",
                "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noDuplicateGateIds() {
        List<String> ids = GateConvergenceV9.gates().stream()
                .map(Gate::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyGateHasDescriptionBlockerAndElimination() {
        for (Gate gate : GateConvergenceV9.gates()) {
            assertThat(gate.description()).isNotBlank();
            assertThat(gate.expectedElimination()).isNotBlank();
            if (gate.status() != Status.GREEN_JVM) {
                assertThat(gate.blocker()).isNotBlank();
            }
        }
    }

    @Test
    void runnerGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV9.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RUNNER)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("runner"));
    }

    @Test
    void releaseGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV9.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RELEASE)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("tag"));
    }

    @Test
    void greenJvmGatesClosedThisPhase() {
        assertThat(GateConvergenceV9.gates()).filteredOn(
                gate -> gate.status() == Status.GREEN_JVM)
                .extracting(Gate::id)
                .containsExactlyInAnyOrder(
                        "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noFakeGreenReported() {
        Set<String> green = Set.of(
                "TD-076", "TD-079", "TD-080");
        for (Gate gate : GateConvergenceV9.gates()) {
            if (gate.status() == Status.GREEN_JVM) {
                assertThat(green).contains(gate.id());
            }
        }
    }

    @Test
    void unknownGateIdRejected() {
        assertThatThrownBy(() -> GateConvergenceV9.gate("TD-999"))
                .isInstanceOf(IllegalArgumentException.class);
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
            "TD-076,Phase 44",
            "TD-079,Phase 43",
            "TD-080,Phase 43"
    })
    void gateLookupAccurate(String id, String expectedBlocker) {
        Gate gate = GateConvergenceV9.gate(id);
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
            "TD-048,Phase 44",
            "TD-049,Phase 44",
            "K8S-001,Phase 44",
            "REL-001,Phase 44",
            "BM-001,Phase 44",
            "BM-002,Phase 44",
            "TD-051,Phase 44",
            "TD-054,Phase 44",
            "TD-059,Phase 44",
            "TD-060,Phase 44",
            "TD-063,Phase 44",
            "TD-066,Phase 44",
            "TD-069,Phase 44",
            "TD-072,Phase 44",
            "TD-075,Phase 44",
            "TD-078,Phase 44",
            "TD-076,Phase 44"
    })
    void eliminationPhaseAccurate(String id,
                                  String expectedPhase) {
        assertThat(GateConvergenceV9.gate(id)
                .expectedElimination())
                .isEqualTo(expectedPhase);
    }

    @ParameterizedTest(name = "runner count {0}")
    @CsvSource({
            "TD-048,RUNNER",
            "TD-049,RUNNER",
            "K8S-001,RUNNER",
            "BM-001,RUNNER",
            "BM-002,RUNNER",
            "TD-051,RUNNER",
            "TD-054,RUNNER",
            "TD-059,RUNNER",
            "TD-060,RUNNER",
            "TD-063,RUNNER",
            "TD-066,RUNNER",
            "TD-069,RUNNER",
            "TD-072,RUNNER",
            "TD-078,RUNNER",
            "REL-001,RELEASE",
            "TD-075,RELEASE"
    })
    void statusClassificationAccurate(String id,
                                      String expected) {
        Gate gate = GateConvergenceV9.gate(id);
        assertThat(gate.status().name())
                .isEqualTo(expected.equals("RUNNER")
                        ? Status.REGISTERED_RUNNER.name()
                        : Status.REGISTERED_RELEASE.name());
    }
}
