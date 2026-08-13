package io.tieringkv.platform;

import io.tieringkv.ci.GateConvergenceV12;
import io.tieringkv.cluster.scheduler.AutonomousComplianceAuditor;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.sql.coprocessor.DynamicPushdownPlanner;
import io.tieringkv.transaction.async.MultiCloudOnePhaseScaleOut;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration
        .CloudTimeSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 46 生产门禁（JVM 级）：规模化/窗口全族/仲裁/合规/凭据 v4。 */
public class Phase46ProductionGateTest {

    @Test
    void scaleOutGate() {
        MultiCloudOnePhaseScaleOut scaleOut = scaleOut();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        scaleOut.attachResolvedTimestamp(resolved);
        assertThat(scaleOut.commit("t1", topology(), 100)
                .onePhase()).isTrue();
        scaleOut.markCloudUnavailable("c2");
        scaleOut.markCloudUnavailable("c3");
        assertThat(scaleOut.commit("t1",
                topology()).onePhase()).isFalse();
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void windowFamilyGate() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "a", "z", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .AVG_OVER);
        List<Row> result = executor.executeCompound(request,
                List.of(new Row("a", 10), new Row("a", 20),
                        new Row("a", 30)));
        assertThat(result).extracting(Row::value)
                .containsExactly(10.0, 15.0, 20.0);
    }

    @Test
    void dynamicPlannerGate() {
        DynamicPushdownPlanner planner =
                new DynamicPushdownPlanner(0.5, 1);
        planner.record(100, 1000, 1000);
        assertThat(planner.shouldPushdown(100, 100, 10)
                .pushdown()).isTrue();
        assertThat(planner.shouldPushdown(100, 5, 10)
                .pushdown()).isFalse();
    }

    @Test
    void arbitrationGate() {
        CrossCloudTsoArbitration clock = arbitration();
        long first = clock.timestamp();
        assertThat(clock.timestamp())
                .isGreaterThanOrEqualTo(first);
        clock.restore(1000);
        assertThat(clock.timestamp()).isGreaterThan(1000);
    }

    @Test
    void complianceGate() {
        AutonomousComplianceAuditor auditor =
                new AutonomousComplianceAuditor();
        auditor.record("executed moves=3");
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
        assertThat(auditor.size()).isEqualTo(1);
    }

    @Test
    void credentialV4Gate() {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
        var result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void gateConvergenceV12RegistryPresent() {
        assertThat(GateConvergenceV12.gates()).hasSize(19);
        assertThat(GateConvergenceV12.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @Test
    void v29ReleasePipelineDocumented() throws Exception {
        String content = java.nio.file.Files.readString(
                java.nio.file.Path.of(".github", "workflows",
                        "release.yml"));
        assertThat(content).contains("v2.9.0");
    }

    @ParameterizedTest(name = "clouds={0} zones={1} elig={2}")
    @CsvSource({
            "1,3,2,true",
            "2,3,2,true",
            "2,3,1,false",
            "3,3,2,true",
            "3,3,1,false",
            "4,3,2,true",
            "4,3,1,false",
            "5,3,2,true",
            "5,3,1,false",
            "6,4,3,true",
            "6,4,2,false",
            "7,5,3,true",
            "7,5,2,false",
            "8,6,4,true"
    })
    void parameterizedScaleOutGates(int clouds, int zones,
                                    int eligibleZones,
                                    boolean expected) {
        MultiCloudOnePhaseScaleOut scaleOut =
                new MultiCloudOnePhaseScaleOut();
        for (int c = 1; c <= clouds; c++) {
            for (int z = 1; z <= zones; z++) {
                scaleOut.registerZone("c" + c, "z" + z,
                        z <= eligibleZones);
            }
        }
        Map<String, Set<String>> topo = new LinkedHashMap<>();
        for (int c = 1; c <= clouds; c++) {
            Set<String> zoneSet = new HashSet<>();
            for (int z = 1; z <= zones; z++) {
                zoneSet.add("z" + z);
            }
            topo.put("c" + c, zoneSet);
        }
        assertThat(scaleOut.commit("t", topo).onePhase())
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
                                .LEAD);
        assertThat(executor.executeCompound(request, data))
                .hasSize(rows);
    }

    @ParameterizedTest(name = "alpha {0}")
    @ValueSource(doubles = {0.1, 0.25, 0.5, 0.75, 0.9,
            1.0, 0.05, 0.4})
    void parameterizedPlannerGates(double alpha) {
        DynamicPushdownPlanner planner =
                new DynamicPushdownPlanner(alpha, 1);
        planner.record(100, 1000, 1000);
        assertThat(planner.shouldPushdown(100, 100, 10)
                .pushdown()).isTrue();
    }

    private static MultiCloudOnePhaseScaleOut scaleOut() {
        MultiCloudOnePhaseScaleOut scaleOut =
                new MultiCloudOnePhaseScaleOut();
        for (int c = 1; c <= 3; c++) {
            for (int z = 1; z <= 3; z++) {
                scaleOut.registerZone("c" + c, "z" + z,
                        z <= 2);
            }
        }
        return scaleOut;
    }

    private static Map<String, Set<String>> topology() {
        Map<String, Set<String>> topology =
                new LinkedHashMap<>();
        for (int c = 1; c <= 3; c++) {
            Set<String> zones = new HashSet<>();
            for (int z = 1; z <= 3; z++) {
                zones.add("z" + z);
            }
            topology.put("c" + c, zones);
        }
        return topology;
    }

    private static CrossCloudTsoArbitration arbitration() {
        return new CrossCloudTsoArbitration(
                List.of(new CloudTimeSource("aws", 0),
                        new CloudTimeSource("gcp", 0),
                        new CloudTimeSource("azure", 0)),
                100, 1000);
    }
}
