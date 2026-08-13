package io.tieringkv.platform;

import io.tieringkv.ci.GateConvergenceV13;
import io.tieringkv.ci.RunnerExecutionArchive;
import io.tieringkv.cluster.scheduler.RegulatoryComplianceCertificate;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.GlobalUnifiedOnePhaseArbitration;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource
        .SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 47 生产门禁（JVM 级）：统一仲裁/RL/量子授时/监管证书/凭据 v5。 */
public class Phase47ProductionGateTest {

    @Test
    void unifiedArbitrationGate() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                arbitration();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        assertThat(arbitration.commit("t1", clouds(), 100)
                .onePhase()).isTrue();
        arbitration.markCloudUnavailable("c2");
        arbitration.markCloudUnavailable("c3");
        assertThat(arbitration.commit("t1",
                clouds()).onePhase()).isFalse();
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void rlAgentGate() {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(0.5, 0.0, 100);
        for (int i = 0; i < 100; i++) {
            agent.learn(
                    ReinforcementPushdownAgent.Action.PUSHDOWN,
                    10);
        }
        assertThat(agent.decide())
                .isEqualTo(
                        ReinforcementPushdownAgent.Action
                                .PUSHDOWN);
    }

    @Test
    void quantumClockGate() {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.HYBRID, 10);
        long first = source.timestamp(1000);
        assertThat(source.timestamp(1000))
                .isGreaterThan(first);
        source.restore(2000);
        assertThat(source.timestamp(0))
                .isGreaterThanOrEqualTo(2001);
    }

    @Test
    void regulatoryCertificateGate() {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        var certificate = cert.issue("digest", "auditor");
        cert.rotateKey();
        assertThat(cert.verify(certificate)).isTrue();
        assertThat(cert.keyVersion()).isEqualTo(2);
    }

    @Test
    void credentialV5Gate() {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
        var result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void gateConvergenceV13RegistryPresent() {
        assertThat(GateConvergenceV13.gates()).hasSize(19);
        assertThat(GateConvergenceV13.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @Test
    void runnerArchiveGate() {
        RunnerExecutionArchive archive =
                new RunnerExecutionArchive();
        archive.record("TD-048", true, "evidence-1");
        archive.record("TD-048", true, "evidence-2");
        assertThat(archive.forGate("TD-048")).hasSize(2);
        assertThat(archive.size()).isEqualTo(2);
    }

    @Test
    void v30ReleasePipelineDocumented() throws Exception {
        String content = java.nio.file.Files.readString(
                java.nio.file.Path.of(".github", "workflows",
                        "release.yml"));
        assertThat(content).contains("v3.0.0");
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
    void parameterizedUnifiedGates(int clouds, int zones,
                                   int eligibleZones,
                                   boolean expected) {
        GlobalUnifiedOnePhaseArbitration arbitration =
                new GlobalUnifiedOnePhaseArbitration();
        for (int c = 1; c <= clouds; c++) {
            for (int z = 1; z <= zones; z++) {
                arbitration.registerZone("c" + c, "z" + z,
                        z <= eligibleZones);
            }
        }
        Set<String> cloudSet = new HashSet<>();
        for (int c = 1; c <= clouds; c++) {
            cloudSet.add("c" + c);
        }
        assertThat(arbitration.commit("t", cloudSet)
                .onePhase()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "epsilon {0}")
    @ValueSource(doubles = {0, 0.05, 0.1, 0.2, 0.3, 0.5,
            0.7, 1.0})
    void parameterizedRlGates(double epsilon) {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(0.5, epsilon,
                        10);
        agent.learn(
                ReinforcementPushdownAgent.Action.PUSHDOWN,
                5);
        for (int i = 0; i < 50; i++) {
            agent.decide();
        }
        assertThat(agent.decisions()).isEqualTo(50);
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(longs = {0, 5, 10, 50, 100, 500, 1000,
            2000})
    void parameterizedQuantumGates(long delay) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.SATELLITE, delay);
        long ts = source.timestamp(1000);
        assertThat(ts).isEqualTo(1000 + delay);
    }

    private static GlobalUnifiedOnePhaseArbitration arbitration() {
        GlobalUnifiedOnePhaseArbitration arbitration =
                new GlobalUnifiedOnePhaseArbitration();
        for (int c = 1; c <= 3; c++) {
            for (int z = 1; z <= 3; z++) {
                arbitration.registerZone("c" + c, "z" + z,
                        z <= 2);
            }
        }
        return arbitration;
    }

    private static Set<String> clouds() {
        Set<String> clouds = new HashSet<>();
        clouds.add("c1");
        clouds.add("c2");
        clouds.add("c3");
        return clouds;
    }
}
