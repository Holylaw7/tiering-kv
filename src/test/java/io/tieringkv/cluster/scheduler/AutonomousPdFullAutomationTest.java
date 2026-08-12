package io.tieringkv.cluster.scheduler;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation
        .AutomationResult;
import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation
        .RiskLevel;
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

/** 自治 PD 全自动（ADR-0224）：风险分级 + 自动执行 + 熔断 + 审批。 */
class AutonomousPdFullAutomationTest {

    @Test
    void lowRiskAutoExecutes() {
        AutonomousPdFullAutomation automation =
                automation(3);
        AutomationResult result = automation.execute(
                loads(1, 3), 100);
        assertThat(result.risk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.executed()).isTrue();
        assertThat(result.moves()).isEqualTo(3);
    }

    @Test
    void highRiskQueuedForApproval() {
        AutonomousPdFullAutomation automation =
                automation(2);
        AutomationResult result = automation.execute(
                loads(1, 3), 100);
        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.queuedForApproval()).isTrue();
        assertThat(result.executed()).isFalse();
        assertThat(automation.approvalQueue()).hasSize(3);
    }

    @Test
    void approvePendingExecutes() {
        AutonomousPdFullAutomation automation =
                automation(2);
        automation.execute(loads(1, 3), 100);
        AutomationResult result = automation.approvePending(
                loads(1, 3), 100);
        assertThat(result.executed()).isTrue();
        assertThat(result.moves()).isEqualTo(3);
        assertThat(automation.approvalQueue()).isEmpty();
    }

    @Test
    void circuitBreakRejects() {
        AutonomousPdFullAutomation automation =
                automation(3);
        automation.manualCircuitBreak("review");
        AutomationResult result = automation.execute(
                loads(1, 3), 100);
        assertThat(result.executed()).isFalse();
        assertThat(result.queuedForApproval()).isFalse();
        assertThat(automation.circuitBroken()).isTrue();
    }

    @Test
    void resetCircuitRestores() {
        AutonomousPdFullAutomation automation =
                automation(3);
        automation.manualCircuitBreak("review");
        automation.resetCircuit();
        AutomationResult result = automation.execute(
                loads(1, 3), 100);
        assertThat(result.executed()).isTrue();
    }

    @Test
    void nullLoadsRejected() {
        AutonomousPdFullAutomation automation =
                automation(3);
        assertThatThrownBy(() -> automation.execute(null, 100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        assertThatThrownBy(() -> new AutonomousPdFullAutomation(
                null, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutonomousPdFullAutomation(
                integration(3), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void auditTracksAutoExecution() {
        AutonomousPdFullAutomation automation =
                automation(3);
        automation.execute(loads(1, 3), 100);
        assertThat(automation.audit()).anyMatch(
                entry -> entry.contains("auto executed"));
    }

    @Test
    void auditTracksQueue() {
        AutonomousPdFullAutomation automation =
                automation(2);
        automation.execute(loads(1, 3), 100);
        assertThat(automation.audit()).anyMatch(
                entry -> entry.contains("high risk"));
    }

    @Test
    void auditTracksCircuitBreak() {
        AutonomousPdFullAutomation automation =
                automation(3);
        automation.manualCircuitBreak("review");
        assertThat(automation.audit()).anyMatch(
                entry -> entry.contains("manual circuit break"));
    }

    @Test
    void approvePendingEmptyReturnsNotExecuted() {
        AutonomousPdFullAutomation automation =
                automation(3);
        AutomationResult result = automation.approvePending(
                loads(1, 3), 100);
        assertThat(result.executed()).isFalse();
        assertThat(result.queuedForApproval()).isFalse();
    }

    @Test
    void highRiskQueueHoldsMoves() {
        AutonomousPdFullAutomation automation =
                automation(1);
        automation.execute(loads(2, 3), 100);
        assertThat(automation.approvalQueue()).hasSize(6);
    }

    @ParameterizedTest(name = "overloaded={0} under={1} limit={2}")
    @CsvSource({
            "1,1,1,true",
            "1,2,2,true",
            "1,3,3,true",
            "1,4,4,true",
            "1,5,5,true",
            "2,2,4,true",
            "2,3,6,true",
            "3,3,9,true",
            "1,1,0,false",
            "1,2,1,false",
            "1,3,2,false",
            "1,4,3,false",
            "1,5,4,false",
            "2,2,3,false",
            "2,3,5,false",
            "3,3,8,false",
            "1,3,1,false",
            "2,2,1,false",
            "2,3,2,false",
            "3,2,5,false",
            "3,2,6,true",
            "4,2,8,true",
            "4,2,7,false",
            "5,2,10,true",
            "5,2,9,false",
            "1,6,6,true",
            "1,6,5,false",
            "2,4,8,true",
            "2,4,7,false",
            "3,4,12,true"
    })
    void parameterizedRiskMatrix(int overloaded, int under,
                                 int lowRiskMaxMoves,
                                 boolean expectedLow) {
        AutonomousPdFullAutomation automation =
                automation(lowRiskMaxMoves);
        RiskLevel risk = automation.assessRisk(
                loads(overloaded, under), 100);
        assertThat(risk == RiskLevel.LOW)
                .isEqualTo(expectedLow);
    }

    @ParameterizedTest(name = "overloaded={0} under={1} limit={2}")
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
            "1,1,0,false,true",
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
                                    int lowRiskMaxMoves,
                                    boolean expectedExecuted,
                                    boolean expectedQueued) {
        AutonomousPdFullAutomation automation =
                automation(lowRiskMaxMoves);
        AutomationResult result = automation.execute(
                loads(overloaded, under), 100);
        assertThat(result.executed())
                .isEqualTo(expectedExecuted);
        assertThat(result.queuedForApproval())
                .isEqualTo(expectedQueued);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13})
    void parameterizedLowRiskThresholds(int threshold) {
        AutonomousPdFullAutomation automation =
                automation(threshold);
        RiskLevel risk = automation.assessRisk(loads(1, 3), 100);
        assertThat(risk)
                .isEqualTo(3 > threshold
                        ? RiskLevel.HIGH : RiskLevel.LOW);
    }

    private static AutonomousPdFullAutomation automation(
            int lowRiskMaxMoves) {
        return new AutonomousPdFullAutomation(
                integration(lowRiskMaxMoves), lowRiskMaxMoves);
    }

    private static GlobalAutonomyPdIntegration integration(
            int lowRiskMaxMoves) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < 8; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 2), "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        return new GlobalAutonomyPdIntegration(discovery,
                new AutonomousPdScheduler(
                        Math.max(4, lowRiskMaxMoves * 2)),
                autonomy, 100);
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
