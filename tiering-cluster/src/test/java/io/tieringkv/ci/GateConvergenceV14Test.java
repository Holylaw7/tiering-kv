package io.tieringkv.ci;

import io.tieringkv.ci.GateConvergenceV14.Gate;
import io.tieringkv.ci.GateConvergenceV14.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 真实执行门禁收敛 v14（ADR-0248）：登记精确 + 发布归档。 */
class GateConvergenceV14Test {

    @Test
    void allExpectedGatesRegistered() {
        List<Gate> gates = GateConvergenceV14.gates();
        assertThat(gates).extracting(Gate::id).contains(
                "TD-048", "TD-049", "K8S-001", "REL-001",
                "BM-001", "BM-002", "TD-051", "TD-054",
                "TD-059", "TD-060", "TD-063", "TD-066",
                "TD-069", "TD-072", "TD-075", "TD-078",
                "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noDuplicateGateIds() {
        List<String> ids = GateConvergenceV14.gates().stream()
                .map(Gate::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void everyGateHasDescriptionBlockerAndElimination() {
        for (Gate gate : GateConvergenceV14.gates()) {
            assertThat(gate.description()).isNotBlank();
            assertThat(gate.expectedElimination()).isNotBlank();
            if (gate.status() != Status.GREEN_JVM) {
                assertThat(gate.blocker()).isNotBlank();
            }
        }
    }

    @Test
    void runnerGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV14.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RUNNER)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("runner"));
    }

    @Test
    void releaseGatesAccuratelyRegistered() {
        assertThat(GateConvergenceV14.gates()).filteredOn(
                gate -> gate.status() == Status.REGISTERED_RELEASE)
                .allSatisfy(gate -> assertThat(gate.blocker())
                        .contains("tag"));
    }

    @Test
    void greenJvmGatesClosedThisPhase() {
        assertThat(GateConvergenceV14.gates()).filteredOn(
                gate -> gate.status() == Status.GREEN_JVM)
                .extracting(Gate::id)
                .containsExactlyInAnyOrder(
                        "TD-076", "TD-079", "TD-080");
    }

    @Test
    void noFakeGreenReported() {
        for (Gate gate : GateConvergenceV14.gates()) {
            if (gate.status() == Status.GREEN_JVM) {
                assertThat(gate.id())
                        .isIn("TD-076", "TD-079", "TD-080");
            }
        }
    }

    @Test
    void unknownGateIdRejected() {
        assertThatThrownBy(() -> GateConvergenceV14.gate("TD-999"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runnerGatesCarryPhase49Elimination() {
        assertThat(GateConvergenceV14.gates()).filteredOn(
                gate -> gate.status() != Status.GREEN_JVM)
                .allSatisfy(gate -> assertThat(
                        gate.expectedElimination())
                        .isEqualTo("Phase 49"));
    }

    @Test
    void releaseRecordArchiveRecordsAndFilters() {
        ReleaseRecordArchive archive = new ReleaseRecordArchive();
        archive.record("v3.1.0", "TD-048", true, "container e2e");
        archive.record("v3.1.0", "REL-001", false, "tag pending");
        archive.record("v3.2.0", "TD-048", true, "rerun");
        assertThat(archive.size()).isEqualTo(3);
        assertThat(archive.forVersion("v3.1.0")).hasSize(2);
        assertThat(archive.forVersion("v3.2.0")).hasSize(1);
    }

    @Test
    void releaseRecordArchiveRejectsBlank() {
        ReleaseRecordArchive archive = new ReleaseRecordArchive();
        assertThatThrownBy(() -> archive.record("", "TD-048",
                true, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> archive.record("v3.1.0",
                "TD-048", true, " "))
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
            "TD-076,Phase 49",
            "TD-079,Phase 48",
            "TD-080,Phase 48"
    })
    void gateLookupAccurate(String id, String expectedBlocker) {
        Gate gate = GateConvergenceV14.gate(id);
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
            "TD-048,Phase 49",
            "TD-049,Phase 49",
            "K8S-001,Phase 49",
            "REL-001,Phase 49",
            "BM-001,Phase 49",
            "BM-002,Phase 49",
            "TD-051,Phase 49",
            "TD-054,Phase 49",
            "TD-059,Phase 49",
            "TD-060,Phase 49",
            "TD-063,Phase 49",
            "TD-066,Phase 49",
            "TD-069,Phase 49",
            "TD-072,Phase 49",
            "TD-075,Phase 49",
            "TD-078,Phase 49",
            "TD-076,Phase 49",
            "TD-079,Phase 48"
    })
    void eliminationPhaseAccurate(String id,
                                  String expectedPhase) {
        assertThat(GateConvergenceV14.gate(id)
                .expectedElimination())
                .isEqualTo(expectedPhase);
    }
}
