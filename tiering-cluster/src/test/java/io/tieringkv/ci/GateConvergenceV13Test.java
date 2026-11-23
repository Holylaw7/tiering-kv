package io.tieringkv.ci;

import io.tieringkv.ci.GateConvergenceV13.Gate;
import io.tieringkv.ci.GateConvergenceV13.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实执行门禁收敛 v13（ADR-0241）：登记精确 + 执行归档。 */
class GateConvergenceV13Test {

    @Test
    void allExpectedGatesRegistered() {
        List<Gate> gates = GateConvergenceV13.gates();
        assertThat(gates).extracting(Gate::id).contains(
                "TD-048", "TD-049", "K8S-001", "REL-001",
                "BM-001", "BM-002", "TD-051", "TD-054",
                "TD-059", "TD-060", "TD-063", "TD-066",
                "TD-069", "TD-072", "TD-075", "TD-078",
                "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noDuplicateGateIds() {
        List<String> ids = GateConvergenceV13.gates().stream()
                .map(Gate::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyGateHasDescriptionBlockerAndElimination() {
        for (Gate gate : GateConvergenceV13.gates()) {
            assertThat(gate.description()).isNotBlank();
            assertThat(gate.expectedElimination()).isNotBlank();
            if (gate.status() != Status.GREEN_JVM) {
                assertThat(gate.blocker()).isNotBlank();
            }
        }
    }

    @Test
    void runnerGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV13.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RUNNER)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("runner"));
    }

    @Test
    void releaseGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV13.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RELEASE)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("tag"));
    }

    @Test
    void greenJvmGatesClosedThisPhase() {
        assertThat(GateConvergenceV13.gates()).filteredOn(
                gate -> gate.status() == Status.GREEN_JVM)
                .extracting(Gate::id)
                .containsExactlyInAnyOrder(
                        "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noFakeGreenReported() {
        for (Gate gate : GateConvergenceV13.gates()) {
            if (gate.status() == Status.GREEN_JVM) {
                assertThat(gate.id())
                        .isIn("TD-076", "TD-079", "TD-080");
            }
        }
    }

    @Test
    void unknownGateIdRejected() {
        assertThatThrownBy(() -> GateConvergenceV13.gate("TD-999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runnerGatesCarryPhase48Elimination() {
        assertThat(GateConvergenceV13.gates()).filteredOn(
                gate -> gate.status() != Status.GREEN_JVM)
                .allSatisfy(gate -> assertThat(
                        gate.expectedElimination())
                        .isEqualTo("Phase 48"));
    }

    @Test
    void runnerExecutionArchiveRecordsAndFilters() {
        RunnerExecutionArchive archive =
                new RunnerExecutionArchive();
        archive.record("TD-048", true, "container e2e passed");
        archive.record("TD-048", false, "flaky retry");
        archive.record("TD-049", true, "disk chaos passed");
        assertThat(archive.size()).isEqualTo(3);
        assertThat(archive.forGate("TD-048")).hasSize(2);
        assertThat(archive.forGate("TD-049")).hasSize(1);
    }

    @Test
    void runnerExecutionArchiveRejectsBlank() {
        RunnerExecutionArchive archive =
                new RunnerExecutionArchive();
        assertThatThrownBy(() -> archive.record("", true, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> archive.record("TD-048",
                true, " "))
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
            "TD-076,Phase 48",
            "TD-079,Phase 47",
            "TD-080,Phase 47"
    })
    void gateLookupAccurate(String id, String expectedBlocker) {
        Gate gate = GateConvergenceV13.gate(id);
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
            "TD-048,Phase 48",
            "TD-049,Phase 48",
            "K8S-001,Phase 48",
            "REL-001,Phase 48",
            "BM-001,Phase 48",
            "BM-002,Phase 48",
            "TD-051,Phase 48",
            "TD-054,Phase 48",
            "TD-059,Phase 48",
            "TD-060,Phase 48",
            "TD-063,Phase 48",
            "TD-066,Phase 48",
            "TD-069,Phase 48",
            "TD-072,Phase 48",
            "TD-075,Phase 48",
            "TD-078,Phase 48",
            "TD-076,Phase 48",
            "TD-079,Phase 47"
    })
    void eliminationPhaseAccurate(String id,
                                  String expectedPhase) {
        assertThat(GateConvergenceV13.gate(id)
                .expectedElimination())
                .isEqualTo(expectedPhase);
    }
}
