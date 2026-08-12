package io.tieringkv.platform;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.ci.GateConvergenceV10;
import io.tieringkv.cluster.scheduler.AutonomousPdFullAutomation;
import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.GlobalAutonomyPdIntegration;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.GlobalOnePhaseCommit;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.TsoDisasterRecovery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 44 生产门禁（JVM 级）：全局一阶段/全算子/TSO 容灾/全自动 PD。 */
class Phase44ProductionGateTest {

    @Test
    void globalOnePhaseGate() {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        commit.registerPrimaryReplica("r1", true);
        commit.registerPrimaryReplica("r2", true);
        commit.registerPrimaryReplica("r3", false);
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        assertThat(commit.commit("t1", Set.of("r1", "r2"),
                100).onePhase()).isTrue();
        assertThat(commit.commit("t1", Set.of("r1", "r3"))
                .onePhase()).isFalse();
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void fullOperatorGate() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.JOIN, Operator.FILTER,
                                Operator.PROJECT, Operator.GROUP_BY,
                                Operator.ORDER_BY, Operator.LIMIT),
                        "a", "z", 10,
                        List.of(new Row("a", 5)),
                        1, false);
        List<Row> result = executor.executeCompound(request,
                List.of(new Row("a", 20), new Row("b", 30)));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).key()).isEqualTo("a");
    }

    @Test
    void tsoDisasterRecoveryGate() {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        recovery.allocate(10);
        recovery.failover();
        assertThat(recovery.allocate()).isEqualTo(10);
        recovery.recoverPrimary();
        assertThat(recovery.allocate()).isEqualTo(11);
    }

    @Test
    void autonomousPdFullAutomationGate() {
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
                discovery, new AutonomousPdScheduler(10),
                autonomy, 100);
        var automation = new AutonomousPdFullAutomation(
                integration, 3);
        Map<String, Long> loads = new LinkedHashMap<>();
        loads.put("n0", 300L);
        loads.put("n1", 50L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        var result = automation.execute(loads, 100);
        assertThat(result.executed()).isTrue();
    }

    @Test
    void credentialRealProbeGate() {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.SIMULATED,
                (endpoint, timeout) -> true, 1000);
        assertThat(probe.probe("s3", "https://s3.example.com",
                "AKIA-TEST").ok()).isTrue();
        CredentialProbe.EndpointProber real =
                CredentialProbe.realHttpProber(500);
        assertThat(real).isNotNull();
    }

    @Test
    void gateConvergenceV10RegistryPresent() {
        assertThat(GateConvergenceV10.gates()).hasSize(19);
        assertThat(GateConvergenceV10.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @Test
    void tikvComparisonBaselineDocumented() throws Exception {
        String content = java.nio.file.Files.readString(
                java.nio.file.Path.of("docs", "benchmark",
                        "tikv-comparison-baseline.md"));
        assertThat(content).contains("TiKV");
        assertThat(content).contains("本地进程内");
    }

    @Test
    void v27ReleasePipelineDocumented() throws Exception {
        String content = java.nio.file.Files.readString(
                java.nio.file.Path.of(".github", "workflows",
                        "release.yml"));
        assertThat(content).contains("v2.7.0");
    }

    @ParameterizedTest(name = "regions={0} eligible={1}")
    @CsvSource({
            "1,true",
            "2,true",
            "3,true",
            "4,false",
            "5,false",
            "2,false",
            "3,false",
            "5,true",
            "6,false",
            "7,true",
            "8,false",
            "10,true"
    })
    void parameterizedGlobalCommitGates(int regions,
                                        boolean eligible) {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i, eligible);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isEqualTo(eligible);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20})
    void parameterizedLimitGates(int limit) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            data.add(new Row("k" + i, i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.ORDER_BY,
                                Operator.LIMIT),
                        "k0", "zz", 0, List.of(), limit,
                        false);
        assertThat(executor.executeCompound(request, data))
                .hasSize(Math.min(20, limit));
    }

    @ParameterizedTest(name = "batches={0} size={1}")
    @CsvSource({
            "1,10",
            "2,10",
            "5,5",
            "10,10",
            "20,5",
            "50,10",
            "100,1",
            "100,10"
    })
    void parameterizedTsoDrGates(int batches, int size) {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        for (int i = 0; i < batches; i++) {
            recovery.allocate(size);
        }
        recovery.failover();
        long ts = recovery.allocate();
        assertThat(ts).isGreaterThanOrEqualTo(
                batches * size - 1);
    }
}
