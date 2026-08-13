package io.tieringkv.platform;

import io.tieringkv.cluster.scheduler.AutonomousComplianceAuditor;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.CompoundCoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.transaction.async.MultiCloudOnePhaseScaleOut;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration;
import io.tieringkv.transaction.tso.CrossCloudTsoArbitration
        .CloudTimeSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 46 参数化边缘矩阵：规模化/窗口全族/仲裁/合规/凭据 v4。 */
class Phase46EdgeMatrixTest {

    @ParameterizedTest(name = "{0} a={1} b={2} c={3}")
    @CsvSource({
            "SCALE,1,3,2",
            "SCALE,2,3,2",
            "SCALE,2,3,1",
            "SCALE,3,3,2",
            "SCALE,3,3,1",
            "SCALE,4,3,2",
            "SCALE,4,3,1",
            "SCALE,5,3,2",
            "SCALE,5,3,1",
            "WINDOW,3,0,3",
            "WINDOW,5,1,5",
            "WINDOW,10,2,10",
            "ARB,1000,100,50",
            "ARB,1000,950,50",
            "ARB,2000,1900,100",
            "COMPLIANCE,1,1,1",
            "COMPLIANCE,5,5,1",
            "COMPLIANCE,10,10,1",
            "CRED,1,1,1",
            "CRED,1,1,0",
            "CRED,1,0,0",
            "CRED,0,1,0",
            "SCALE,6,4,3",
            "SCALE,6,4,2",
            "COMPLIANCE,20,20,1"
    })
    void edgeMatrix(String feature, int a, int b, int c) {
        switch (feature) {
            case "SCALE" -> scaleEdge(a, b, c);
            case "WINDOW" -> windowEdge(a, b);
            case "ARB" -> arbEdge(a, b, c);
            case "COMPLIANCE" -> complianceEdge(a);
            case "CRED" -> credEdge(a == 1, b == 1,
                    c == 1);
            default -> throw new IllegalArgumentException(
                    "unknown feature " + feature);
        }
    }

    private static void scaleEdge(int clouds, int zones,
                                  int eligibleZones) {
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
        boolean zoneOk = eligibleZones > zones / 2;
        assertThat(scaleOut.commit("t", topo).onePhase())
                .isEqualTo(zoneOk);
    }

    private static void windowEdge(int rows, int partitions) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("p" + (i % Math.max(1, partitions)),
                    i * 10.0));
        }
        CompoundCoprocessorRequest request =
                new CompoundCoprocessorRequest(
                        List.of(Operator.WINDOW),
                        "p0", "zz", 0, List.of(), List.of(),
                        Integer.MAX_VALUE, false,
                        CompoundCoprocessorRequest.WindowFunction
                                .LAG);
        assertThat(executor.executeCompound(request, data))
                .hasSize(rows);
    }

    private static void arbEdge(long high, long low, long window) {
        CrossCloudTsoArbitration clock =
                new CrossCloudTsoArbitration(List.of(), 1000,
                        window);
        clock.addSource(new CloudTimeSource("aws", high));
        clock.addSource(new CloudTimeSource("gcp", high));
        clock.addSource(new CloudTimeSource("azure", high));
        clock.timestamp();
        clock.clearSources();
        clock.addSource(new CloudTimeSource("aws", low));
        clock.addSource(new CloudTimeSource("gcp", low));
        clock.addSource(new CloudTimeSource("azure", low));
        long ts = clock.timestamp();
        assertThat(ts).isGreaterThanOrEqualTo(high);
    }

    private static void complianceEdge(int records) {
        AutonomousComplianceAuditor auditor =
                new AutonomousComplianceAuditor();
        for (int i = 0; i < records; i++) {
            auditor.record("event " + i);
        }
        assertThat(auditor.verify(auditor.exportAudit()))
                .isTrue();
    }

    private static void credEdge(boolean reachable,
                                 boolean authValid,
                                 boolean allowed) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> reachable, 500);
        var result = probe.probeWithPermission("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid,
                (endpoint, credential) -> allowed);
        assertThat(result.ok())
                .isEqualTo(reachable && authValid && allowed);
    }
}
