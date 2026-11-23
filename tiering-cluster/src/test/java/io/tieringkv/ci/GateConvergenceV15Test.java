package io.tieringkv.ci;

import io.tieringkv.ci.GateConvergenceV15.Disposition;
import io.tieringkv.ci.GateConvergenceV15.Gate;
import io.tieringkv.ci.GateConvergenceV15.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 门禁收敛表 v15 + 闭环归档（ADR-0255）。 */
class GateConvergenceV15Test {

    @Test
    void registryHasAllGates() {
        assertThat(GateConvergenceV15.gates()).hasSize(23);
        assertThat(GateConvergenceV15.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @Test
    void closedCountMatchesJvmClosedGates() {
        assertThat(GateConvergenceV15.closedCount())
                .isEqualTo(GateConvergenceV15.gates().stream()
                        .filter(gate -> gate.disposition()
                                == Disposition.CLOSED)
                        .count());
    }

    @Test
    void pendingCountMatchesNonClosedGates() {
        assertThat(GateConvergenceV15.pendingCount())
                .isEqualTo(GateConvergenceV15.gates().stream()
                        .filter(gate -> gate.disposition()
                                != Disposition.CLOSED)
                        .count());
    }

    @Test
    void summaryIsExportable() {
        String summary = GateConvergenceV15.summary();
        assertThat(summary).contains("GateConvergenceV15");
        assertThat(summary).contains("closed=");
        assertThat(summary).contains("pending=");
    }

    @Test
    void gateLookupReturnsExpectedGate() {
        Gate gate = GateConvergenceV15.gate("TD-081");
        assertThat(gate.description())
                .contains("cross-regulatory");
    }

    @Test
    void unknownGateThrows() {
        assertThatThrownBy(() -> GateConvergenceV15.gate("NOPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closedGatesAreJvmGreen() {
        assertThat(GateConvergenceV15.gates().stream()
                .filter(gate -> gate.disposition()
                        == Disposition.CLOSED))
                .allSatisfy(gate -> assertThat(gate.status())
                        .isEqualTo(Status.GREEN_JVM));
    }

    @ParameterizedTest(name = "gate {0} resolves")
    @MethodSource("allGateIds")
    void everyGateResolves(String gateId) {
        Gate gate = GateConvergenceV15.gate(gateId);
        assertThat(gate.description()).isNotBlank();
        assertThat(gate.expectedElimination()).isNotBlank();
    }

    @ParameterizedTest(name = "closed gate {0}")
    @MethodSource("closedGateIds")
    void closedGatesFullyDisposed(String gateId) {
        Gate gate = GateConvergenceV15.gate(gateId);
        assertThat(gate.disposition())
                .isEqualTo(Disposition.CLOSED);
        assertThat(gate.status()).isEqualTo(Status.GREEN_JVM);
    }

    @ParameterizedTest(name = "pending gate {0}")
    @MethodSource("pendingGateIds")
    void pendingGatesRecordBlocker(String gateId) {
        Gate gate = GateConvergenceV15.gate(gateId);
        assertThat(gate.disposition())
                .isNotEqualTo(Disposition.CLOSED);
        assertThat(gate.blocker()).isNotBlank();
    }

    @Test
    void archiveRecordsAndFilters() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        archive.record("TD-081", "Phase 49",
                Disposition.CLOSED, "jvm green");
        archive.record("TD-048", "Phase 49",
                Disposition.ENV_BLOCKED, "requires linux runner");
        assertThat(archive.size()).isEqualTo(2);
        assertThat(archive.forGate("TD-081")).hasSize(1);
    }

    @Test
    void archiveTrendReportExports() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        archive.addTrend("RTT", 120, "ms", "CROSS_MACHINE");
        archive.addTrend("RTO", 300, "ms", "CROSS_MACHINE");
        String report = archive.crossRegionTrendReport();
        assertThat(report).contains("RTT,120.0,ms");
        assertThat(report).contains("RTO,300.0,ms");
    }

    @Test
    void archiveAlertHistory() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        archive.alert("BM-001", "P99", 2.0, 1.0);
        assertThat(archive.alerts()).hasSize(1);
        assertThat(archive.alerts().get(0).metric())
                .isEqualTo("P99");
    }

    @Test
    void emptyArchiveReportMarksPending() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        assertThat(archive.crossRegionTrendReport())
                .contains("pending");
    }

    @Test
    void archiveRejectsBlankRecord() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        assertThatThrownBy(() -> archive.record("", "p",
                Disposition.CLOSED, "e"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void archiveRejectsBlankTrend() {
        RunnerClosureArchive archive = new RunnerClosureArchive();
        assertThatThrownBy(() -> archive.addTrend("", 1,
                "ms", "scope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> allGateIds() {
        return GateConvergenceV15.gates().stream()
                .map(Gate::id);
    }

    static Stream<String> closedGateIds() {
        return GateConvergenceV15.gates().stream()
                .filter(gate -> gate.disposition()
                        == Disposition.CLOSED)
                .map(Gate::id);
    }

    static Stream<String> pendingGateIds() {
        return GateConvergenceV15.gates().stream()
                .filter(gate -> gate.disposition()
                        != Disposition.CLOSED)
                .map(Gate::id);
    }
}
