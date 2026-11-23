package io.tieringkv.platform;

import io.tieringkv.cluster.scheduler.RegulatoryMappingEngine;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.MultiAgentPushdownCoordinator;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.MultiOrgFederationArbitration;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter
        .SimulatedHardwareClock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 48 参数化边缘矩阵：联邦/多智能体/硬件/法规映射/凭据 v6。 */
class Phase48EdgeMatrixTest {

    @ParameterizedTest(name = "{0} a={1} b={2} c={3}")
    @CsvSource({
            "FED,2,2,2",
            "FED,2,2,1",
            "FED,3,2,2",
            "FED,3,2,1",
            "FED,4,2,2",
            "FED,4,2,1",
            "FED,5,3,2",
            "FED,5,3,1",
            "AGENT,1,5,1",
            "AGENT,2,5,1",
            "AGENT,3,5,1",
            "AGENT,4,5,1",
            "HW,1000,10,1",
            "HW,1000,100,1",
            "HW,2000,50,1",
            "MAP,1,1,1",
            "MAP,3,2,1",
            "MAP,5,3,1",
            "CRED,1,1,1",
            "CRED,1,1,0",
            "CRED,1,0,1",
            "CRED,0,1,1",
            "FED,6,2,3",
            "FED,6,2,2",
            "AGENT,5,5,1",
            "MAP,10,5,1",
            "HW,5000,100,1",
            "CRED,1,1,1",
            "CRED,1,1,0",
            "FED,7,2,2",
            "FED,7,2,1",
            "AGENT,2,10,1",
            "MAP,7,4,1",
            "HW,10000,500,1",
            "CRED,1,0,0"
    })
    void edgeMatrix(String feature, int a, int b, int c) {
        switch (feature) {
            case "FED" -> fedEdge(a, b, c);
            case "AGENT" -> agentEdge(a);
            case "HW" -> hwEdge(a, b);
            case "MAP" -> mapEdge(a);
            case "CRED" -> credEdge(a == 1, b == 1,
                    c == 1);
            default -> throw new IllegalArgumentException(
                    "unknown feature " + feature);
        }
    }

    private static void fedEdge(int orgs, int cloudsPerOrg,
                                int eligibleZones) {
        MultiOrgFederationArbitration arbitration =
                new MultiOrgFederationArbitration();
        Set<String> cloudSet = new HashSet<>();
        for (int o = 1; o <= orgs; o++) {
            for (int c = 1; c <= cloudsPerOrg; c++) {
                String cloud = "c" + o + "-" + c;
                arbitration.registerOrganization(cloud,
                        "org-" + o);
                for (int z = 1; z <= 3; z++) {
                    arbitration.registerZone(cloud, "z" + z,
                            z <= eligibleZones);
                }
                cloudSet.add(cloud);
            }
        }
        boolean cloudOk = eligibleZones > 1;
        assertThat(arbitration.commit("t", cloudSet)
                .onePhase()).isEqualTo(cloudOk);
    }

    private static void agentEdge(int agents) {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        for (int i = 0; i < agents; i++) {
            coordinator.registerAgent("q" + i,
                    new ReinforcementPushdownAgent(0.5, 0.0,
                            10), 1.0);
        }
        assertThat(coordinator.agentCount()).isEqualTo(agents);
        assertThat(coordinator.federatedDecide("q0"))
                .isNotNull();
    }

    private static void hwEdge(long base, long drift) {
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(
                        new SimulatedHardwareClock(base,
                                drift), 0);
        assertThat(adapter.timestamp())
                .isEqualTo(base + drift);
    }

    private static void mapEdge(int events) {
        RegulatoryMappingEngine engine =
                new RegulatoryMappingEngine();
        engine.registerRule("GDPR", "A17", "delete");
        for (int i = 0; i < events; i++) {
            engine.mapEvent("delete");
        }
        assertThat(engine.evidenceCount("delete"))
                .isEqualTo(events);
    }

    private static void credEdge(boolean reachable,
                                 boolean authValid,
                                 boolean allowed) {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> reachable, 500);
        var result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> reachable,
                (endpoint, credential) -> authValid,
                (endpoint, credential) -> allowed,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5, 100);
        assertThat(result.ok())
                .isEqualTo(reachable && authValid && allowed);
    }
}
