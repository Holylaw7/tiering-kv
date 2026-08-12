package io.tieringkv.platform;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.ci.GateConvergenceV9;
import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.CrossRegionOnePhaseCommit;
import io.tieringkv.transaction.tso.TsoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 43 生产门禁（JVM 级）：跨区一阶段/多算子/TSO/自治联动/凭据。 */
class Phase43ProductionGateTest {

    @Test
    void crossRegionOnePhaseGate() {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", true);
        commit.registerPrimaryReplica("r3", false);
        assertThat(commit.commit("t1",
                Set.of("r1", "r2")).onePhase()).isTrue();
        assertThat(commit.commit("t1",
                Set.of("r1", "r3")).onePhase()).isFalse();
        assertThat(commit.commitTwoPhase("t1",
                Set.of("r1", "r2")).onePhase()).isFalse();
    }

    @Test
    void compoundCoprocessorGate() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER, Operator.PROJECT,
                                Operator.AGGREGATE),
                        "a", "z", 10);
        List<Row> result = executor.executeCompound(request,
                List.of(new Row("a", 5), new Row("b", 20),
                        new Row("c", 30)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).value()).isEqualTo(500);
    }

    @Test
    void tsoGate() {
        TsoService tso = new TsoService();
        long[] first = tso.allocate(10);
        long[] second = tso.allocate(5);
        assertThat(first[0]).isZero();
        assertThat(second[0]).isEqualTo(10);
        assertThat(tso.restore(3)).isEqualTo(14);
    }

    @Test
    void globalAutonomyGate() {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        discovery.heartbeat(
                new TopologyDiscovery.Heartbeat(
                        "n0", "r1", "az-1", 0), 50);
        discovery.heartbeat(
                new TopologyDiscovery.Heartbeat(
                        "n1", "r1", "az-1", 0), 50);
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(1),
                autonomy, 100);
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 300L);
        loads.put("n1", 50L);
        var result = integration.planAndExecute(loads);
        assertThat(result.executed()).isEqualTo(1);
        assertThat(integration.topologyVersion())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void credentialProbeGate() {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.SIMULATED,
                (endpoint, timeout) -> true, 1000);
        assertThat(probe.probe("s3",
                "https://s3.example.com",
                "AKIA-TEST").ok()).isTrue();
        assertThat(probe.degraded()).isFalse();
    }

    @Test
    void gateConvergenceV9RegistryPresent() {
        assertThat(GateConvergenceV9.gates()).hasSize(19);
        assertThat(GateConvergenceV9.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @ParameterizedTest(name = "regions {0} eligible {1}")
    @CsvSource({
            "1,true",
            "2,true",
            "3,true",
            "4,false",
            "5,false"
    })
    void parameterizedCrossRegionGates(int regions,
                                       boolean allEligible) {
        CrossRegionOnePhaseCommit commit =
                new CrossRegionOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i < regions - 1 || allEligible);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isEqualTo(allEligible);
    }

    @ParameterizedTest(name = "rows {0} threshold {1}")
    @CsvSource({
            "3,10",
            "5,50",
            "10,100",
            "20,500",
            "50,1000"
    })
    void parameterizedCoprocessorGates(int rows,
                                       double threshold) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, i * 100.0));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.FILTER, Operator.PROJECT),
                        "k0", "zz", threshold);
        List<Row> result = executor.executeCompound(request,
                data);
        assertThat(result).allMatch(row -> row.value()
                >= threshold);
    }

    @ParameterizedTest(name = "batch {0}")
    @CsvSource({"1,0", "10,0", "100,0", "1000,0", "10000,0"})
    void parameterizedTsoGates(int batch, long firstStart) {
        TsoService tso = new TsoService();
        long[] range = tso.allocate(batch);
        assertThat(range[0]).isEqualTo(firstStart);
        assertThat(range[1] - range[0])
                .isEqualTo(batch - 1L);
    }

    @ParameterizedTest(name = "limit {0}")
    @CsvSource({"1,1", "2,2", "5,5", "10,10", "20,20"})
    void parameterizedAutonomyGates(int limit, int expected) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < 4; i++) {
            discovery.heartbeat(
                    new TopologyDiscovery.Heartbeat(
                            "n" + i, "r" + (i % 2),
                            "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        var integration = new GlobalAutonomyPdIntegration(
                discovery, new AutonomousPdScheduler(limit),
                autonomy, 100);
        Map<String, Long> loads = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            loads.put("n" + i, i < 2 ? 300L : 50L);
        }
        var result = integration.planAndExecute(loads);
        assertThat(result.executed()).isLessThanOrEqualTo(expected);
    }

    @ParameterizedTest(name = "mode {0} reachable {1}")
    @CsvSource({
            "SIMULATED,true",
            "REAL,true",
            "REAL,false",
            "AUTO,true"
    })
    void parameterizedCredentialGates(String mode,
                                      boolean reachable) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.valueOf(mode),
                (endpoint, timeout) -> reachable, 1000);
        var result = probe.probe("s3",
                "https://s3.example.com", "secret");
        assertThat(result.degraded())
                .isEqualTo(!(reachable && result.credentialValid()));
    }
}
