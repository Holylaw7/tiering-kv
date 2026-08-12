package io.tieringkv.cluster.scheduler;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration.AuditEntry;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration.PlanResult;
import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;
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

/** 自治 PD 与全球自治联动（ADR-0217）：计划/护栏/回滚矩阵。 */
class GlobalAutonomyPdIntegrationTest {

    @Test
    void topologyVersionBumpsWhenHealthyCountChanges() {
        TopologyDiscovery discovery = discovery(2, 2);
        var integration = integration(discovery, 1, 100);
        assertThat(integration.detectTopologyChange())
                .isEqualTo(1);
        discovery.heartbeat(new Heartbeat("n3", "r3", "az-3", 0),
                50);
        assertThat(integration.detectTopologyChange())
                .isEqualTo(2);
    }

    @Test
    void topologyVersionStableWhenUnchanged() {
        TopologyDiscovery discovery = discovery(2, 2);
        var integration = integration(discovery, 1, 100);
        integration.detectTopologyChange();
        assertThat(integration.detectTopologyChange())
                .isEqualTo(1);
    }

    @Test
    void balancedLoadsExecuteNoMoves() {
        TopologyDiscovery discovery = discovery(2, 2);
        var integration = integration(discovery, 10, 100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("n0", 50L, "n1", 50L)));
        assertThat(result.executed()).isZero();
        assertThat(result.plan()).isEmpty();
        assertThat(result.rolledBack()).isFalse();
    }

    @Test
    void overloadedLoadsExecuteWithinRoundLimit() {
        TopologyDiscovery discovery = discovery(4, 2);
        var integration = integration(discovery, 1, 100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("n0", 300L, "n1", 50L,
                        "n2", 50L, "n3", 50L)));
        assertThat(result.executed()).isEqualTo(1);
        assertThat(result.plan()).hasSize(3);
    }

    @Test
    void policyFreezeBlocksExecution() {
        TopologyDiscovery discovery = discovery(2, 2);
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r1", "g1", 1.0, 0.0, 1000);
        for (int i = 0; i < 5; i++) {
            autonomy.record("r1", Action.TIGHTEN, 10);
        }
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(10), autonomy,
                100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("n0", 300L, "n1", 50L)));
        assertThat(result.executed()).isZero();
        assertThat(result.guardrailReasons()).anyMatch(
                reason -> reason.contains("policy freeze"));
    }

    @Test
    void validatorRejectionRollsBackExecutedMoves() {
        TopologyDiscovery discovery = discovery(4, 2);
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(10),
                autonomy(), 100,
                move -> !move.from().equals("n1"));
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 200L);
        loads.put("n1", 200L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        PlanResult result = integration.planAndExecute(loads);
        assertThat(result.executed()).isEqualTo(2);
        assertThat(result.rolledBack()).isTrue();
        assertThat(integration.audit()).anyMatch(
                entry -> entry.type().equals("ROLLBACK"));
    }

    @Test
    void circuitOpenBlocksExecution() {
        TopologyDiscovery discovery = discovery(4, 2);
        AutonomousPdScheduler scheduler =
                new AutonomousPdScheduler(10);
        scheduler.openCircuit("test");
        var integration = new GlobalAutonomyPdIntegration(
                discovery, scheduler, autonomy(), 100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("n0", 300L, "n1", 50L,
                        "n2", 50L, "n3", 50L)));
        assertThat(result.executed()).isZero();
        assertThat(result.guardrailReasons()).anyMatch(
                reason -> reason.contains("circuit open"));
    }

    @Test
    void nullLoadsRejected() {
        var integration = integration(discovery(2, 2), 10, 100);
        assertThatThrownBy(() -> integration.planAndExecute(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyLoadsRejected() {
        var integration = integration(discovery(2, 2), 10, 100);
        assertThatThrownBy(() -> integration.planAndExecute(
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidConstructorRejected() {
        TopologyDiscovery discovery = discovery(2, 2);
        assertThatThrownBy(() -> new GlobalAutonomyPdIntegration(
                null, new AutonomousPdScheduler(1), autonomy(),
                100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(1), autonomy(),
                0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regionQuorumBlocksLastHealthyNode() {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 0),
                50);
        discovery.heartbeat(new Heartbeat("n2", "r2", "az-2", 0),
                50);
        var integration = integration(discovery, 10, 100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("n1", 200L, "n2", 50L)));
        assertThat(result.executed()).isZero();
        assertThat(result.guardrailReasons()).anyMatch(
                reason -> reason.contains("blocked"));
    }

    @Test
    void azSpreadBlocksLastHealthyNode() {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 0),
                50);
        discovery.heartbeat(new Heartbeat("n2", "r1", "az-2", 0),
                50);
        discovery.heartbeat(new Heartbeat("n3", "r2", "az-3", 0),
                50);
        var integration = integration(discovery, 10, 100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("n1", 200L, "n2", 50L, "n3", 50L)));
        assertThat(result.executed()).isZero();
        assertThat(result.guardrailReasons()).anyMatch(
                reason -> reason.contains("blocked"));
    }

    @Test
    void unknownSourceNodeNotBlockedByGuardrail() {
        TopologyDiscovery discovery = discovery(1, 1);
        var integration = integration(discovery, 10, 100);
        PlanResult result = integration.planAndExecute(
                loads(Map.of("ghost", 200L, "n0", 50L)));
        assertThat(result.executed()).isEqualTo(1);
    }

    @Test
    void newRoundResetsExecutionBudget() {
        TopologyDiscovery discovery = discovery(4, 2);
        AutonomousPdScheduler scheduler =
                new AutonomousPdScheduler(1);
        var integration = new GlobalAutonomyPdIntegration(
                discovery, scheduler, autonomy(), 100);
        integration.planAndExecute(loads(
                Map.of("n0", 300L, "n1", 50L,
                        "n2", 50L, "n3", 50L)));
        assertThat(integration.planAndExecute(loads(
                Map.of("n0", 300L, "n1", 50L,
                        "n2", 50L, "n3", 50L))).executed())
                .isEqualTo(1);
    }

    @Test
    void auditTracksExecutedAndGuardrail() {
        TopologyDiscovery discovery = discovery(4, 2);
        var integration = integration(discovery, 10, 100);
        integration.planAndExecute(loads(
                Map.of("n0", 300L, "n1", 50L,
                        "n2", 50L, "n3", 50L)));
        assertThat(integration.audit()).anyMatch(
                entry -> entry.type().equals("TOPOLOGY"));
        assertThat(integration.audit()).anyMatch(
                entry -> entry.type().equals("EXECUTED"));
    }

    @Test
    void auditEntriesAreImmutablyExposed() {
        var integration = integration(discovery(2, 2), 10, 100);
        integration.planAndExecute(loads(
                Map.of("n0", 50L, "n1", 50L)));
        assertThat(integration.audit()).isNotEmpty();
        assertThatThrownBy(() -> integration.audit().add(
                new AuditEntry("X", "y")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10, 20})
    void parameterizedNodeCounts(int count) {
        TopologyDiscovery discovery = discovery(count, count);
        var integration = integration(discovery, count, 100);
        integration.detectTopologyChange();
        assertThat(integration.topologyVersion())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "maxLoad {0}")
    @ValueSource(longs = {10, 50, 100, 500, 1000})
    void parameterizedMaxLoads(long maxLoad) {
        TopologyDiscovery discovery = discovery(2, 2);
        var integration = integration(discovery, 10, maxLoad);
        PlanResult result = integration.planAndExecute(loads(
                Map.of("n0", maxLoad * 3, "n1", maxLoad / 2)));
        assertThat(result.executed()).isLessThanOrEqualTo(10);
        assertThat(result.executed())
                .isLessThanOrEqualTo(result.plan().size());
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void parameterizedRoundLimits(int limit) {
        TopologyDiscovery discovery = discovery(4, 4);
        var integration = integration(discovery, limit, 100);
        PlanResult result = integration.planAndExecute(loads(
                Map.of("n0", 200L, "n1", 200L,
                        "n2", 50L, "n3", 50L)));
        assertThat(result.executed())
                .isLessThanOrEqualTo(limit);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {0, 1, 3, 5})
    void parameterizedTopologyChangeRounds(int rounds) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        var integration = integration(discovery, 10, 100);
        long version = 0;
        for (int i = 0; i < rounds; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + i, "az-" + i, 0), 50);
            version = integration.detectTopologyChange();
        }
        assertThat(version).isEqualTo(rounds);
    }

    @ParameterizedTest(name = "nodes={0} maxLoad={1} load={2}")
    @CsvSource({
            "1,100,100",
            "2,100,150",
            "2,100,300",
            "3,100,200",
            "4,100,250",
            "5,50,120",
            "5,50,300",
            "10,100,500",
            "10,200,800",
            "20,100,300",
            "20,500,1000",
            "3,10,30",
            "3,10,80",
            "4,25,70",
            "4,25,150",
            "6,60,180",
            "6,60,420",
            "8,80,160",
            "8,80,480",
            "12,120,600"
    })
    void parameterizedPlanInvariants(int nodes, long maxLoad,
                                     long load) {
        TopologyDiscovery discovery = discovery(nodes, nodes);
        var integration = integration(discovery, nodes, maxLoad);
        Map<String, Long> loads = new LinkedHashMap<>();
        for (int i = 0; i < nodes; i++) {
            loads.put("n" + i, i == 0 ? load : load / 2);
        }
        PlanResult result = integration.planAndExecute(loads);
        assertThat(result.plan()).isNotNull();
        assertThat(result.executed()).isGreaterThanOrEqualTo(0);
        assertThat(result.executed())
                .isLessThanOrEqualTo(nodes);
        assertThat(result.executed())
                .isLessThanOrEqualTo(result.plan().size() + 1);
        assertThat(result.guardrailReasons()).isNotNull();
    }

    @ParameterizedTest(name = "regionHealthy={0} azHealthy={1}")
    @CsvSource({
            "1,1,0",
            "2,1,0",
            "1,2,2",
            "2,2,1",
            "1,1,0",
            "2,1,0",
            "1,2,2",
            "2,2,1",
            "1,1,0",
            "2,2,1",
            "2,2,1",
            "2,2,1"
    })
    void parameterizedGuardrailScenarios(int regionHealthy,
                                         int azHealthy,
                                         int expectedExecuted) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < regionHealthy; i++) {
            discovery.heartbeat(new Heartbeat(
                    "r1n" + i, "r1", "az-" + (i + 1), 0), 50);
        }
        if (regionHealthy > 0) {
            discovery.heartbeat(new Heartbeat("r1n0", "r1",
                    "az-1", 0), 50);
        }
        if (azHealthy > 1) {
            discovery.heartbeat(new Heartbeat("r1n9", "r1",
                    "az-1", 0), 50);
        }
        discovery.heartbeat(new Heartbeat("n2", "r2", "az-9", 0),
                50);
        var integration = integration(discovery, 10, 100);
        Map<String, Long> loads = new LinkedHashMap<>();
        discovery.nodes().forEach(node -> loads.put(node.nodeId(),
                node.nodeId().equals("n2") ? 50L : 200L));
        PlanResult result = integration.planAndExecute(loads);
        assertThat(result.executed()).isEqualTo(expectedExecuted);
    }

    @ParameterizedTest(name = "denyFrom={0}")
    @CsvSource({
            ",4,false",
            "n0,0,false",
            "n1,2,true",
            "n2,4,false"
    })
    void parameterizedValidatorRollback(String denyFrom,
                                        int expectedExecuted,
                                        boolean expectedRolledBack) {
        TopologyDiscovery discovery = discovery(4, 2);
        String blocked = "".equals(denyFrom) ? "none" : denyFrom;
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(10),
                autonomy(), 100,
                move -> !move.from().equals(blocked));
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 200L);
        loads.put("n1", 200L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        PlanResult result = integration.planAndExecute(loads);
        assertThat(result.executed()).isEqualTo(expectedExecuted);
        assertThat(result.rolledBack())
                .isEqualTo(expectedRolledBack);
    }

    private static GlobalAutonomyPdIntegration integration(
            TopologyDiscovery discovery, int limit, long maxLoad) {
        return new GlobalAutonomyPdIntegration(discovery,
                new AutonomousPdScheduler(limit), autonomy(),
                maxLoad);
    }

    private static TopologyFederatedAutonomy autonomy() {
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r2", "g1", 0.1, 0.0, 100);
        return autonomy;
    }

    private static TopologyDiscovery discovery(int nodes,
                                               int regions) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < nodes; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % regions), "az-" + (i % 2), 0), 50);
        }
        return discovery;
    }

    private static Map<String, Long> loads(
            Map<String, Long> values) {
        return new LinkedHashMap<>(values);
    }
}
