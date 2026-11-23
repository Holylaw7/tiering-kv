package io.tieringkv.platform;

import io.tieringkv.capacity.ai.ReinforcementAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.SignedAttestation;
import io.tieringkv.compliance.SignatureVerifier;
import io.tieringkv.datamesh.MaterializedViewLifecycle;
import io.tieringkv.datamesh.RemoteMaterializationManager;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteDefinition;
import io.tieringkv.datamesh.RemoteStateStore;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.observability.cost.SpotMigrationPlanner;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.PolicyRiskScorer;
import io.tieringkv.security.network.RiskDashboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 38 生产门禁（JVM 级）：持久化/强化学习/签名/迁移/风险。 */
class Phase38ProductionGateTest {

    @TempDir
    Path dir;

    @Test
    void remoteStatePersistRestoreGate() {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1",
                new RemoteMaterializationManager.RemoteSnapshot(
                        "v1", "gcp-us", 42, 2, false, 1000),
                Map.of("k1", 10.0, "k2", 32.0));
        var state = store.load("v1").orElseThrow();
        assertThat(state.value()).isEqualTo(42);
        assertThat(state.keys()).containsEntry("k2", 32.0);
    }

    @Test
    void remoteStateCorruptionFallbackGate() throws Exception {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1",
                new RemoteMaterializationManager.RemoteSnapshot(
                        "v1", "gcp-us", 1, 1, false, 1),
                Map.of());
        Path file = dir.resolve("v1.state");
        byte[] bytes = java.nio.file.Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x55;
        java.nio.file.Files.write(file, bytes);
        assertThat(store.load("v1")).isEmpty();
    }

    @Test
    void reinforcementAutonomyGate() {
        ReinforcementAutonomy autonomy =
                new ReinforcementAutonomy(0.5, 0.0, 10.0);
        autonomy.record(Action.RELAX, 1.0);
        assertThat(autonomy.chooseAction())
                .isEqualTo(Action.RELAX);
        assertThat(autonomy.weights().get(Action.RELAX))
                .isGreaterThan(autonomy.weights()
                        .get(Action.TIGHTEN));
    }

    @Test
    void materializedViewLifecycleGate() {
        RemoteMaterializationManager manager =
                new RemoteMaterializationManager(
                        new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us")));
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        var snapshot = manager.snapshot("v1");
        MaterializedViewLifecycle lifecycle =
                new MaterializedViewLifecycle();
        var archived = lifecycle.archive(snapshot, 2000);
        assertThat(lifecycle.restore(archived)).isEqualTo(snapshot);
    }

    @Test
    void signedAttestationGate() {
        byte[] key = "secret".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        var attestation = chain.attestations().get(0);
        var signed = SignedAttestation.sign(attestation, key);
        assertThat(new SignatureVerifier().verify(signed, key))
                .isTrue();
        assertThat(new SignatureVerifier().verify(signed,
                "other".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    void signedAttestationTamperGate() {
        byte[] key = "secret".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        var original = chain.attestations().get(0);
        var signed = SignedAttestation.sign(original, key);
        var tampered = new AttestationChain.Attestation(
                original.index(), original.regulation(),
                original.versionId(), 99, original.prevHash(),
                original.hash(), original.timestampMillis());
        assertThat(new SignatureVerifier().verify(
                new SignedAttestation.Signed(tampered,
                        signed.signature()), key)).isFalse();
    }

    @Test
    void spotMigrationGate() {
        SpotMigrationPlanner planner = new SpotMigrationPlanner();
        var plan = planner.plan("t1", "aws-us",
                List.of(
                        new SpotOption("aws-us", 1, 0.0, 100,
                                true),
                        new SpotOption("gcp-us", 3, 0.0, 100,
                                true)),
                new SpotTask("t1", "us", 10, false),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")));
        assertThat(plan).isPresent();
        assertThat(plan.orElseThrow().toCloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void spotMigrationSovereigntyGate() {
        SpotMigrationPlanner planner = new SpotMigrationPlanner();
        assertThat(planner.plan("t1", "aws-us",
                List.of(new SpotOption("aws-eu", 1, 0.0, 100,
                        true)),
                new SpotTask("t1", "us", 10, false),
                new DataResidencyPolicy(Map.of(
                        "aws-eu", "eu")))).isEmpty();
    }

    @Test
    void policyRiskScoringGate() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-2", "subnet-2", true));
        policy.allow("t1", "t2");
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.score()).isEqualTo(30);
        assertThat(risk.privateExposure()).isTrue();
        assertThat(new RiskDashboard().scoreByTenant(policy))
                .containsEntry("t1", 30)
                .containsEntry("t2", 30);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {5, 20})
    void parameterizedRiskTenants(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        policy.allow("t0", "t1");
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.score()).isEqualTo(30);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedRlRounds(int rounds) {
        ReinforcementAutonomy autonomy =
                new ReinforcementAutonomy(0.1, 0.0, 10.0);
        for (int i = 0; i < rounds; i++) {
            autonomy.record(Action.RELAX, 1.0);
        }
        assertThat(autonomy.q(Action.RELAX))
                .isGreaterThan(0.0);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 10})
    void parameterizedStateStoreViews(int count) {
        RemoteStateStore store = new RemoteStateStore(dir);
        for (int i = 0; i < count; i++) {
            store.save("v" + i,
                    new RemoteMaterializationManager
                            .RemoteSnapshot("v" + i, "gcp-us",
                            i, 1, false, i),
                    Map.of());
        }
        for (int i = 0; i < count; i++) {
            assertThat(store.load("v" + i).orElseThrow().value())
                    .isEqualTo(i);
        }
    }

    @Test
    void lifecycleSweepGate() {
        RemoteMaterializationManager manager =
                new RemoteMaterializationManager(
                        new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us")));
        manager.define(new RemoteDefinition("fresh", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        manager.syncChange("fresh",
                new io.tieringkv.datamesh
                        .CdcMaterializedViewRefresher.CdcChange(
                        "k", io.tieringkv.datamesh
                        .CdcMaterializedViewRefresher.ChangeType
                        .INSERT, 1));
        assertThat(new MaterializedViewLifecycle().sweep(manager,
                60_000, System.currentTimeMillis() + 1000))
                .isEmpty();
    }

    @Test
    void riskDashboardNullRejectedGate() {
        assertThatThrownBy(() -> new RiskDashboard()
                .scoreByTenant(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
