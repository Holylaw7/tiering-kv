package io.tieringkv.platform;

import io.tieringkv.ci.GateConvergenceV11;
import io.tieringkv.cluster.scheduler.AutonomousPdUnattended;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.sql.coprocessor.PushdownCostModel;
import io.tieringkv.transaction.async.MultiCloudOnePhaseCommit;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.GlobalTsoClock;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSource;
import io.tieringkv.transaction.tso.GlobalTsoClock.TimeSourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 45 生产门禁（JVM 级）：跨云/窗口/时钟/无人值守/凭据 v3。 */
public class Phase45ProductionGateTest {

    @Test
    void multiCloudGate() {
        MultiCloudOnePhaseCommit commit = cloudCommit();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        commit.attachResolvedTimestamp(resolved);
        assertThat(commit.commit("t1",
                Set.of("aws", "gcp", "azure"), 100)
                .onePhase()).isTrue();
        commit.markUnavailable("gcp");
        assertThat(commit.commit("t1",
                Set.of("aws", "gcp", "azure")).onePhase())
                .isFalse();
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void windowFunctionGate() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "a", "z", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .RANK);
        List<Row> result = executor.executeCompound(request,
                List.of(new Row("a", 10), new Row("a", 10),
                        new Row("a", 20)));
        assertThat(result).extracting(Row::value)
                .containsExactly(1.0, 1.0, 2.0);
    }

    @Test
    void pushdownCostGate() {
        PushdownCostModel model = new PushdownCostModel(0);
        assertThat(model.shouldPushdown(100, 100, 10)
                .pushdown()).isTrue();
        assertThat(model.shouldPushdown(100, 10, 100)
                .pushdown()).isFalse();
    }

    @Test
    void globalClockGate() {
        GlobalTsoClock clock = new GlobalTsoClock(
                List.of(new TimeSource(TimeSourceType.GPS, 100),
                        new TimeSource(TimeSourceType.ATOMIC, 200),
                        new TimeSource(TimeSourceType.NTP, 300)),
                100);
        long first = clock.timestamp();
        assertThat(clock.timestamp()).isGreaterThanOrEqualTo(
                first);
        clock.restore(1000);
        assertThat(clock.timestamp()).isGreaterThan(1000);
    }

    @Test
    void unattendedGate() {
        AutonomousPdUnattended unattended = unattendedHelper();
        var result = unattended.execute(loadsHelper(), 100);
        assertThat(result.executed()).isTrue();
        assertThat(unattended.complianceReport().digest())
                .isNotBlank();
    }

    @Test
    void credentialV3Gate() {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
        var result = probe.probeAuthenticated("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void gateConvergenceV11RegistryPresent() {
        assertThat(GateConvergenceV11.gates()).hasSize(19);
        assertThat(GateConvergenceV11.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @Test
    void v28ReleasePipelineDocumented() throws Exception {
        String content = java.nio.file.Files.readString(
                java.nio.file.Path.of(".github", "workflows",
                        "release.yml"));
        assertThat(content).contains("v2.8.0");
    }

    @ParameterizedTest(name = "clouds={0} eligible={1}")
    @CsvSource({
            "1,1,true",
            "2,2,true",
            "2,1,false",
            "3,2,true",
            "3,1,false",
            "4,3,true",
            "4,2,false",
            "5,3,true",
            "5,2,false",
            "6,4,true",
            "6,3,false",
            "7,4,true"
    })
    void parameterizedMultiCloudGates(int clouds, int eligible,
                                      boolean expected) {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        for (int i = 0; i < clouds; i++) {
            commit.registerCloud("c" + i, i < eligible);
        }
        Set<String> cloudSet = new java.util.HashSet<>();
        for (int i = 0; i < clouds; i++) {
            cloudSet.add("c" + i);
        }
        assertThat(commit.commit("t", cloudSet).onePhase())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {3, 5, 10, 20, 50})
    void parameterizedWindowGates(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + (i % 2), i));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "k0", "zz", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .ROW_NUMBER);
        assertThat(executor.executeCompound(request, data))
                .hasSize(rows);
    }

    @ParameterizedTest(name = "rows={0} local={1} transfer={2}")
    @CsvSource({
            "10,100,10,true",
            "10,10,100,false",
            "100,100,10,true",
            "100,10,100,false",
            "1000,100,50,true",
            "1000,50,100,false",
            "1,1000,10,true",
            "1,10,1000,false"
    })
    void parameterizedCostGates(long rows, long local,
                                long transfer,
                                boolean expected) {
        PushdownCostModel model = new PushdownCostModel(0);
        assertThat(model.shouldPushdown(rows, local, transfer)
                .pushdown()).isEqualTo(expected);
    }

    private static MultiCloudOnePhaseCommit cloudCommit() {
        MultiCloudOnePhaseCommit commit =
                new MultiCloudOnePhaseCommit();
        commit.registerCloud("aws", true);
        commit.registerCloud("gcp", true);
        commit.registerCloud("azure", false);
        return commit;
    }

    public static AutonomousPdUnattended unattendedHelper() {
        io.tieringkv.cluster.topology.TopologyDiscovery discovery =
                new io.tieringkv.cluster.topology.TopologyDiscovery(
                        1000);
        for (int i = 0; i < 8; i++) {
            discovery.heartbeat(
                    new io.tieringkv.cluster.topology
                            .TopologyDiscovery.Heartbeat(
                            "n" + i, "r" + (i % 2),
                            "az-" + (i % 2), 0), 50);
        }
        io.tieringkv.capacity.ai.TopologyFederatedAutonomy autonomy =
                new io.tieringkv.capacity.ai
                        .TopologyFederatedAutonomy();
        autonomy.registerRegion("r0", "g1", 0.1, 0.0, 100);
        autonomy.registerRegion("r1", "g1", 0.1, 0.0, 100);
        var integration = new io.tieringkv.cluster.scheduler
                .GlobalAutonomyPdIntegration(discovery,
                new io.tieringkv.cluster.scheduler
                        .AutonomousPdScheduler(10),
                autonomy, 100);
        return new AutonomousPdUnattended(
                new io.tieringkv.cluster.scheduler
                        .AutonomousPdFullAutomation(
                        integration, 3), 0.5, 1, 10);
    }

    public static java.util.Map<String, Long> loadsHelper() {
        java.util.Map<String, Long> loads =
                new java.util.LinkedHashMap<>();
        loads.put("n0", 300L);
        loads.put("n1", 50L);
        loads.put("n2", 50L);
        loads.put("n3", 50L);
        return loads;
    }
}
