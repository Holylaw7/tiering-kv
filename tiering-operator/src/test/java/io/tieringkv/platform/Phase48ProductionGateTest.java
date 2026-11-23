package io.tieringkv.platform;

import io.tieringkv.ci.GateConvergenceV14;
import io.tieringkv.ci.ReleaseRecordArchive;
import io.tieringkv.cluster.scheduler.RegulatoryMappingEngine;
import io.tieringkv.config.CredentialProbe;
import io.tieringkv.sql.coprocessor.MultiAgentPushdownCoordinator;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent;
import io.tieringkv.transaction.async.MultiOrgFederationArbitration;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter;
import io.tieringkv.transaction.tso.QuantumSatelliteHardwareAdapter
        .SimulatedHardwareClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 48 生产门禁（JVM 级）：联邦/多智能体/硬件适配/法规映射/凭据 v6。 */
public class Phase48ProductionGateTest {

    @Test
    void federationGate() {
        MultiOrgFederationArbitration arbitration =
                federation();
        ResolvedTimestampService resolved =
                new ResolvedTimestampService();
        arbitration.attachResolvedTimestamp(resolved);
        assertThat(arbitration.commit("t1", clouds(), 100)
                .onePhase()).isTrue();
        assertThat(resolved.resolvedTs()).isEqualTo(100);
    }

    @Test
    void multiAgentGate() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("q0",
                new ReinforcementPushdownAgent(1.0, 0.0, 100),
                1.0);
        coordinator.registerAgent("q1",
                new ReinforcementPushdownAgent(1.0, 0.0, 100),
                1.0);
        coordinator.learn("q0",
                ReinforcementPushdownAgent.Action.PUSHDOWN, 5);
        assertThat(coordinator.federatedDecide("q0"))
                .isEqualTo(
                        ReinforcementPushdownAgent.Action
                                .PUSHDOWN);
    }

    @Test
    void hardwareAdapterGate() {
        SimulatedHardwareClock clock =
                new SimulatedHardwareClock(1000, 0);
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(clock, 10);
        long first = adapter.timestamp();
        assertThat(first).isEqualTo(1010);
        clock.fail();
        assertThat(adapter.timestamp()).isEqualTo(first);
        assertThat(adapter.failures()).isEqualTo(1);
    }

    @Test
    void regulatoryMappingGate() {
        RegulatoryMappingEngine engine =
                new RegulatoryMappingEngine();
        engine.registerRule("GDPR", "A17", "delete");
        String entry = engine.mapEvent("delete");
        assertThat(entry).contains("GDPR/A17");
        assertThat(engine.evidenceCount("delete"))
                .isEqualTo(1);
    }

    @Test
    void credentialV6Gate() {
        CredentialProbe probe = new CredentialProbe(
                CredentialProbe.Mode.REAL,
                (endpoint, timeout) -> true, 500);
        var result = probe.probeWithLatency("s3",
                "https://s3.example.com", "secret",
                (endpoint, timeout) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, credential) -> true,
                (endpoint, timeout) -> 5, 100);
        assertThat(result.ok()).isTrue();
    }

    @Test
    void gateConvergenceV14RegistryPresent() {
        assertThat(GateConvergenceV14.gates()).hasSize(19);
        assertThat(GateConvergenceV14.gates()).allSatisfy(
                gate -> assertThat(gate.id()).isNotBlank());
    }

    @Test
    void releaseArchiveGate() {
        ReleaseRecordArchive archive =
                new ReleaseRecordArchive();
        archive.record("v3.1.0", "REL-001", true, "tagged");
        archive.record("v3.1.0", "BM-001", false, "pending");
        assertThat(archive.forVersion("v3.1.0")).hasSize(2);
    }

    @Test
    void v31ReleasePipelineDocumented() throws Exception {
        String content = java.nio.file.Files.readString(
                java.nio.file.Path.of(".github", "workflows",
                        "release.yml"));
        assertThat(content).contains("v3.1.0");
    }

    @ParameterizedTest(name = "orgs={0} cpo={1} zones={2} elig={3}")
    @CsvSource({
            "1,2,3,2,true",
            "2,2,3,2,true",
            "2,2,3,1,false",
            "3,2,3,2,true",
            "3,2,3,1,false",
            "4,2,3,2,true",
            "4,2,3,1,false",
            "5,3,3,2,true",
            "5,3,3,1,false",
            "6,2,4,3,true",
            "6,2,4,2,false",
            "7,2,5,3,true",
            "7,2,5,2,false",
            "8,2,6,4,true"
    })
    void parameterizedFederationGates(int orgs, int cloudsPerOrg,
                                      int zones,
                                      int eligibleZones,
                                      boolean expected) {
        MultiOrgFederationArbitration arbitration =
                new MultiOrgFederationArbitration();
        Set<String> cloudSet = new HashSet<>();
        for (int o = 1; o <= orgs; o++) {
            for (int c = 1; c <= cloudsPerOrg; c++) {
                String cloud = "c" + o + "-" + c;
                arbitration.registerOrganization(cloud,
                        "org-" + o);
                for (int z = 1; z <= zones; z++) {
                    arbitration.registerZone(cloud, "z" + z,
                            z <= eligibleZones);
                }
                cloudSet.add(cloud);
            }
        }
        assertThat(arbitration.commit("t", cloudSet)
                .onePhase()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void parameterizedMultiAgentGates(int agents) {
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

    @ParameterizedTest(name = "drift {0}")
    @ValueSource(longs = {0, 10, 100, 500, 1000, 5000,
            10000, 20000})
    void parameterizedHardwareGates(long drift) {
        QuantumSatelliteHardwareAdapter adapter =
                new QuantumSatelliteHardwareAdapter(
                        new SimulatedHardwareClock(1000,
                                drift), 0);
        assertThat(adapter.timestamp())
                .isEqualTo(1000 + drift);
    }

    private static MultiOrgFederationArbitration federation() {
        MultiOrgFederationArbitration arbitration =
                new MultiOrgFederationArbitration();
        for (int o = 1; o <= 2; o++) {
            for (int c = 1; c <= 2; c++) {
                String cloud = "c" + o + "-" + c;
                arbitration.registerOrganization(cloud,
                        "org-" + o);
                for (int z = 1; z <= 3; z++) {
                    arbitration.registerZone(cloud, "z" + z,
                            z <= 2);
                }
            }
        }
        return arbitration;
    }

    private static Set<String> clouds() {
        Set<String> clouds = new HashSet<>();
        clouds.add("c1-1");
        clouds.add("c1-2");
        clouds.add("c2-1");
        clouds.add("c2-2");
        return clouds;
    }
}
