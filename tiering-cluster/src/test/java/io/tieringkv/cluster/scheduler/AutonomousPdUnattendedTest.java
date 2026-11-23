package io.tieringkv.cluster.scheduler;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation
        .AutomationResult;
import io.tieringkv.cluster.scheduler.AutonomousPdUnattended
        .ComplianceReport;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.cluster.topology.TopologyDiscovery.Heartbeat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 自治 PD 无人值守（ADR-0231）：自校准 + 合规证明 + 熔断。 */
class AutonomousPdUnattendedTest {

    @Test
    void unattendedExecutesLowRisk() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        AutomationResult result = unattended.execute(
                loads(1, 3), 100);
        assertThat(result.executed()).isTrue();
        assertThat(result.moves()).isEqualTo(3);
    }

    @Test
    void recordOutcomeUpdatesEwma() {
        AutonomousPdUnattended unattended =
                unattended(3, 0.5, 10);
        unattended.recordOutcome(new AutomationResult(
                AutonomousPdFullAutomation.RiskLevel.LOW,
                3, true, false, true));
        assertThat(unattended.complianceReport().rollbackRate())
                .isEqualTo(1.0);
        unattended.recordOutcome(new AutomationResult(
                AutonomousPdFullAutomation.RiskLevel.LOW,
                3, true, false, false));
        assertThat(unattended.complianceReport().rollbackRate())
                .isEqualTo(0.5);
    }

    @Test
    void highRollbackRateLowersThreshold() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        long initial = unattended.calibratedThreshold();
        for (int i = 0; i < 5; i++) {
            unattended.recordOutcome(new AutomationResult(
                    AutonomousPdFullAutomation.RiskLevel.LOW,
                    3, true, false, true));
        }
        assertThat(unattended.calibratedThreshold())
                .isLessThanOrEqualTo(initial);
    }

    @Test
    void lowRollbackRateRaisesThreshold() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        long initial = unattended.calibratedThreshold();
        for (int i = 0; i < 10; i++) {
            unattended.recordOutcome(new AutomationResult(
                    AutonomousPdFullAutomation.RiskLevel.LOW,
                    3, true, false, false));
        }
        assertThat(unattended.calibratedThreshold())
                .isGreaterThanOrEqualTo(initial);
    }

    @Test
    void complianceReportContent() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        unattended.execute(loads(1, 3), 100);
        ComplianceReport report = unattended.complianceReport();
        assertThat(report.executions()).isEqualTo(1);
        assertThat(report.rollbacks()).isZero();
        assertThat(report.digest()).isNotBlank();
        assertThat(report.calibratedThreshold())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void circuitBreakDelegates() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        unattended.manualCircuitBreak("review");
        assertThat(unattended.circuitBroken()).isTrue();
        AutomationResult result = unattended.execute(
                loads(1, 3), 100);
        assertThat(result.executed()).isFalse();
    }

    @Test
    void resetCircuitDelegates() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        unattended.manualCircuitBreak("review");
        unattended.resetCircuit();
        assertThat(unattended.circuitBroken()).isFalse();
        assertThat(unattended.execute(loads(1, 3), 100)
                .executed()).isTrue();
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() -> new AutonomousPdUnattended(
                null, 0.1, 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousPdUnattended(
                fullAutomation(3), 0, 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousPdUnattended(
                fullAutomation(3), 0.1, 0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousPdUnattended(
                fullAutomation(3), 0.1, 10, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditTracksExecution() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        unattended.execute(loads(1, 3), 100);
        assertThat(unattended.audit()).anyMatch(
                entry -> entry.contains("unattended execute"));
    }

    @Test
    void thresholdClampedToMin() {
        AutonomousPdUnattended unattended =
                unattended(1, 0.5, 10);
        for (int i = 0; i < 100; i++) {
            unattended.recordOutcome(new AutomationResult(
                    AutonomousPdFullAutomation.RiskLevel.LOW,
                    3, true, false, true));
        }
        assertThat(unattended.calibratedThreshold())
                .isEqualTo(1);
    }

    @Test
    void thresholdClampedToMax() {
        AutonomousPdUnattended unattended =
                unattended(1, 0.5, 10);
        for (int i = 0; i < 100; i++) {
            unattended.recordOutcome(new AutomationResult(
                    AutonomousPdFullAutomation.RiskLevel.LOW,
                    3, true, false, false));
        }
        assertThat(unattended.calibratedThreshold())
                .isEqualTo(10);
    }

    @Test
    void complianceDigestStable() {
        AutonomousPdUnattended unattended = unattended(3, 1, 10);
        unattended.execute(loads(1, 3), 100);
        ComplianceReport first = unattended.complianceReport();
        ComplianceReport second = unattended.complianceReport();
        assertThat(second.digest()).isEqualTo(first.digest());
    }

    @ParameterizedTest(name = "outcomes={0} rollbacks={1} alpha={2}")
    @CsvSource({
            "1,1,1.0,5,5,5",
            "2,2,1.0,5,5,5",
            "3,3,1.0,5,5,5",
            "5,5,1.0,5,5,5",
            "1,0,1.0,5,5,5",
            "2,0,1.0,5,5,5",
            "3,0,1.0,5,5,5",
            "5,0,1.0,5,5,5",
            "10,0,1.0,5,5,5",
            "1,0,0.1,5,5,5",
            "2,0,0.1,5,5,5",
            "3,0,0.1,5,5,5",
            "2,1,1.0,5,5,5",
            "4,2,1.0,5,5,5",
            "6,3,1.0,5,5,5",
            "8,4,1.0,5,5,5",
            "10,5,1.0,5,5,5",
            "1,1,0.5,5,5,5",
            "3,2,0.5,5,5,5",
            "5,3,0.5,5,5,5",
            "7,4,0.5,5,5,5",
            "2,1,0.5,5,5,5",
            "4,2,0.5,5,5,5",
            "6,2,0.5,5,5,5",
            "8,3,0.5,5,5,5",
            "10,4,0.5,5,5,5",
            "1,1,0.9,5,5,5",
            "3,1,0.9,5,5,5",
            "5,1,0.9,5,5,5",
            "7,1,0.9,5,5,5"
    })
    void parameterizedCalibrationDirection(int outcomes,
                                           int rollbacks,
                                           double alpha,
                                           long min, long max,
                                           long expected) {
        AutonomousPdUnattended unattended =
                new AutonomousPdUnattended(fullAutomation(3),
                        alpha, min, max);
        long initial = unattended.calibratedThreshold();
        for (int i = 0; i < outcomes; i++) {
            boolean rolledBack = i < rollbacks;
            unattended.recordOutcome(new AutomationResult(
                    AutonomousPdFullAutomation.RiskLevel.LOW,
                    3, true, false, rolledBack));
        }
        long current = unattended.calibratedThreshold();
        assertThat(current).isEqualTo(expected);
    }

    @ParameterizedTest(name = "overloaded={0} under={1} min={2}")
    @CsvSource({
            "1,3,3,true,false",
            "1,3,2,false,true",
            "2,3,6,true,false",
            "2,3,5,false,true",
            "1,2,2,true,false",
            "1,2,1,false,true",
            "2,2,4,true,false",
            "2,2,3,false,true",
            "3,2,6,true,false",
            "3,2,5,false,true",
            "1,1,1,true,false",
            "1,1,1,true,false",
            "4,2,8,true,false",
            "4,2,7,false,true",
            "5,2,10,true,false",
            "5,2,9,false,true",
            "2,4,8,true,false",
            "2,4,7,false,true",
            "3,4,12,true,false",
            "3,4,11,false,true"
    })
    void parameterizedExecuteMatrix(int overloaded, int under,
                                    long minThreshold,
                                    boolean expectedExecuted,
                                    boolean expectedQueued) {
        AutonomousPdUnattended unattended =
                unattended(minThreshold, 0.5,
                        Math.max(minThreshold, 20));
        AutomationResult result = unattended.execute(
                loads(overloaded, under), 100);
        assertThat(result.executed())
                .isEqualTo(expectedExecuted);
        assertThat(result.queuedForApproval())
                .isEqualTo(expectedQueued);
    }

    @ParameterizedTest(name = "alpha {0}")
    @ValueSource(doubles = {0.05, 0.1, 0.2, 0.3, 0.4, 0.5,
            0.6, 0.7, 0.8, 0.9, 0.95, 1.0, 0.25})
    void parameterizedAlphaValues(double alpha) {
        AutonomousPdUnattended unattended =
                new AutonomousPdUnattended(fullAutomation(3),
                        alpha, 1, 10);
        unattended.recordOutcome(new AutomationResult(
                AutonomousPdFullAutomation.RiskLevel.LOW,
                3, true, false, true));
        assertThat(unattended.complianceReport().rollbackRate())
                .isEqualTo(1.0);
        unattended.recordOutcome(new AutomationResult(
                AutonomousPdFullAutomation.RiskLevel.LOW,
                3, true, false, false));
        assertThat(unattended.complianceReport().rollbackRate())
                .isEqualTo(1.0 - alpha);
    }

    private static AutonomousPdUnattended unattended(
            long min, double alpha, long max) {
        return new AutonomousPdUnattended(fullAutomation(min),
                alpha, min, max);
    }

    private static AutonomousPdFullAutomation fullAutomation(
            long lowRiskMaxMoves) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < 8; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 2), "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        return new AutonomousPdFullAutomation(
                new GlobalAutonomyPdIntegration(discovery,
                        new AutonomousPdScheduler(
                                Math.max(4,
                                        (int) lowRiskMaxMoves * 2)),
                        autonomy, 100), (int) lowRiskMaxMoves);
    }

    private static Map<String, Long> loads(int overloaded,
                                           int under) {
        Map<String, Long> loads = new LinkedHashMap<>();
        for (int i = 0; i < overloaded; i++) {
            loads.put("n" + i, 300L);
        }
        for (int i = overloaded; i < overloaded + under; i++) {
            loads.put("n" + i, 50L);
        }
        return loads;
    }
}
