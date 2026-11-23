package io.tieringkv.platform;

import io.tieringkv.cluster.scheduler.RegulatoryComplianceCertificate;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.GlobalUnifiedOnePhaseArbitration;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource;
import io.tieringkv.transaction.tso.QuantumSatelliteTimeSource
        .SourceKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 47 参数化边缘矩阵：统一仲裁/RL/量子授时/监管证书/凭据 v5。 */
class Phase47EdgeMatrixTest {

    @ParameterizedTest(name = "{0} a={1} b={2} c={3}")
    @CsvSource({
            "UNIFIED,1,3,2",
            "UNIFIED,2,3,2",
            "UNIFIED,2,3,1",
            "UNIFIED,3,3,2",
            "UNIFIED,3,3,1",
            "UNIFIED,4,3,2",
            "UNIFIED,4,3,1",
            "UNIFIED,5,3,2",
            "UNIFIED,5,3,1",
            "RL,10,5,1",
            "RL,50,5,1",
            "RL,100,5,1",
            "QUANTUM,1000,10,1",
            "QUANTUM,1000,100,1",
            "QUANTUM,2000,50,1",
            "CERT,1,1,1",
            "CERT,5,2,1",
            "CERT,10,3,1",
            "CRED,1,1,1",
            "CRED,1,1,0",
            "CRED,1,0,1",
            "CRED,0,1,1",
            "UNIFIED,6,4,3",
            "UNIFIED,6,4,2",
            "CERT,20,5,1"
    })
    void edgeMatrix(String feature, int a, int b, int c) {
        switch (feature) {
            case "UNIFIED" -> unifiedEdge(a, b, c);
            case "RL" -> rlEdge(a);
            case "QUANTUM" -> quantumEdge(a, b);
            case "CERT" -> certEdge(a, b);
            case "CRED" -> credEdge(a == 1, b == 1,
                    c == 1);
            default -> throw new IllegalArgumentException(
                    "unknown feature " + feature);
        }
    }

    private static void unifiedEdge(int clouds, int zones,
                                    int eligibleZones) {
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
        boolean zoneOk = eligibleZones > zones / 2;
        assertThat(arbitration.commit("t", cloudSet)
                .onePhase()).isEqualTo(zoneOk);
    }

    private static void rlEdge(int learns) {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(0.8, 0.05, 100);
        for (int i = 0; i < learns; i++) {
            agent.learn(
                    ReinforcementPushdownAgent.Action.PUSHDOWN,
                    5);
        }
        assertThat(agent.q(
                ReinforcementPushdownAgent.Action.PUSHDOWN))
                .isCloseTo(5.0,
                        org.assertj.core.data.Offset
                                .offset(0.05));
    }

    private static void quantumEdge(long sourceTime, long delay) {
        QuantumSatelliteTimeSource source =
                new QuantumSatelliteTimeSource(
                        SourceKind.HYBRID, delay);
        assertThat(source.corrected(sourceTime))
                .isEqualTo(sourceTime + delay);
    }

    private static void certEdge(int certificates, int rotations) {
        RegulatoryComplianceCertificate cert =
                new RegulatoryComplianceCertificate();
        var first = cert.issue("digest", "auditor");
        for (int i = 1; i < certificates; i++) {
            cert.issue("digest-" + i, "auditor");
        }
        for (int i = 0; i < rotations; i++) {
            cert.rotateKey();
        }
        assertThat(cert.certificates())
                .hasSize(certificates);
        assertThat(cert.verify(first)).isTrue();
    }

    private static void credEdge(boolean reachable,
                                 boolean authValid,
                                 boolean allowed) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> reachable, 500);
        var result = probe.probeWithQuota("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid,
                (endpoint, credential) -> allowed,
                (endpoint, credential) -> true);
        assertThat(result.ok())
                .isEqualTo(reachable && authValid && allowed);
    }
}
