package io.tieringkv.platform;

import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
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
import io.tieringkv.transaction.tso.TsoDisasterRecovery;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 44 参数化边缘矩阵：全局一阶段/全算子/TSO 容灾/全自动/凭据。 */
class Phase44EdgeMatrixTest {

    @ParameterizedTest(name = "{0} a={1} b={2} c={3}")
    @CsvSource({
            "GLOBAL,1,1,1",
            "GLOBAL,2,2,1",
            "GLOBAL,3,3,1",
            "GLOBAL,4,4,1",
            "GLOBAL,5,5,1",
            "GLOBAL,1,2,0",
            "GLOBAL,2,3,0",
            "GLOBAL,3,4,0",
            "GLOBAL,4,5,0",
            "GLOBAL,5,6,0",
            "FULLOP,1,1,1",
            "FULLOP,2,1,1",
            "FULLOP,3,2,2",
            "FULLOP,5,3,3",
            "FULLOP,10,5,5",
            "TSODR,1,10,1",
            "TSODR,2,10,1",
            "TSODR,5,5,1",
            "TSODR,10,10,1",
            "TSODR,20,5,1",
            "AUTO,1,3,3",
            "AUTO,1,3,2",
            "AUTO,2,3,6",
            "CRED,1,1,1",
            "CRED,1,0,0"
    })
    void edgeMatrix(String feature, int a, int b, int c) {
        switch (feature) {
            case "GLOBAL" -> globalEdge(a, b, c == 1);
            case "FULLOP" -> fullOpEdge(a, b, c);
            case "TSODR" -> tsoDrEdge(a, b);
            case "AUTO" -> autoEdge(a, b, c);
            case "CRED" -> credEdge(a, c == 1);
            default -> throw new IllegalArgumentException(
                    "unknown feature " + feature);
        }
    }

    private static void globalEdge(int eligible, int regions,
                                   boolean allEligible) {
        GlobalOnePhaseCommit commit = new GlobalOnePhaseCommit();
        for (int i = 0; i < regions; i++) {
            commit.registerPrimaryReplica("r" + i,
                    i < eligible);
        }
        Set<String> regionSet = new java.util.HashSet<>();
        for (int i = 0; i < regions; i++) {
            regionSet.add("r" + i);
        }
        assertThat(commit.commit("t", regionSet).onePhase())
                .isEqualTo(allEligible);
    }

    private static void fullOpEdge(int rows, int limit,
                                   int expected) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, i * 10.0));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.ORDER_BY,
                                Operator.LIMIT),
                        "k0", "zz", 0, List.of(), limit,
                        false);
        assertThat(executor.executeCompound(request, data))
                .hasSize(expected);
    }

    private static void tsoDrEdge(int batches, int size) {
        TsoDisasterRecovery recovery = new TsoDisasterRecovery();
        for (int i = 0; i < batches; i++) {
            recovery.allocate(size);
        }
        recovery.failover();
        assertThat(recovery.allocate())
                .isGreaterThanOrEqualTo(batches * size - 1);
    }

    private static void autoEdge(int overloaded, int under,
                                 int lowRiskMaxMoves) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < 8; i++) {
            discovery.heartbeat(
                    new TopologyDiscovery.Heartbeat(
                            "n" + i, "r" + (i % 2),
                            "az-" + (i % 2), 0), 50);
        }
        TopologyFederatedAutonomy autonomy =
                new TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        var automation = new AutonomousPdFullAutomation(
                new GlobalAutonomyPdIntegration(discovery,
                        new AutonomousPdScheduler(10),
                        autonomy, 100), lowRiskMaxMoves);
        Map<String, Long> loads = new LinkedHashMap<>();
        for (int i = 0; i < overloaded; i++) {
            loads.put("n" + i, 300L);
        }
        for (int i = overloaded; i < overloaded + under; i++) {
            loads.put("n" + i, 50L);
        }
        var result = automation.execute(loads, 100);
        int moves = overloaded * under;
        assertThat(result.queuedForApproval())
                .isEqualTo(moves > lowRiskMaxMoves);
        assertThat(result.executed())
                .isEqualTo(moves <= lowRiskMaxMoves);
    }

    private static void credEdge(int mode, boolean ok) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.values()[mode],
                (endpoint, timeout) -> ok, 1000);
        var result = probe.probe("s3",
                ok ? "https://s3.example.com" : "",
                ok ? "secret" : "");
        assertThat(result.degraded()).isEqualTo(!ok);
    }
}
