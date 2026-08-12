package io.tieringkv.platform;

import io.tieringkv.capacity.ai.ReinforcementAutonomy;
import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.SignedAttestation;
import io.tieringkv.compliance.SignatureVerifier;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.MaterializedViewLifecycle;
import io.tieringkv.datamesh.RemoteMaterializationManager;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteDefinition;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.datamesh.RemoteStateStore;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.observability.cost.SpotMigrationPlanner;
import io.tieringkv.observability.cost.SpotMigrationPlanner.MigrationPlan;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.PolicyRiskScorer;
import io.tieringkv.security.network.RiskDashboard;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 38 参数化边缘矩阵：持久化/RL/生命周期/签名/迁移/风险。 */
class Phase38EdgeMatrixTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20, 50, 100})
    void stateStoreKeyCounts(int count) {
        RemoteStateStore store = new RemoteStateStore(dir);
        Map<String, Double> keys = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            keys.put("k" + i, i * 1.0);
        }
        store.save("v1", snapshot("v1", count, count, false, 1),
                keys);
        assertThat(store.load("v1").orElseThrow().keys())
                .hasSize(count);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void stateStoreViewCounts(int count) {
        RemoteStateStore store = new RemoteStateStore(dir);
        for (int i = 0; i < count; i++) {
            store.save("v" + i, snapshot("v" + i, i, 1, false, i),
                    Map.of());
        }
        for (int i = 0; i < count; i++) {
            assertThat(store.load("v" + i).orElseThrow().value())
                    .isEqualTo(i);
        }
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 1.5, 100.0, 1_000_000.0})
    void stateStoreValues(double value) {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", value, 1, false, 1),
                Map.of());
        assertThat(store.load("v1").orElseThrow().value())
                .isEqualTo(value);
    }

    @ParameterizedTest(name = "stale {0}")
    @ValueSource(booleans = {true, false})
    void stateStoreStaleFlags(boolean stale) {
        RemoteStateStore store = new RemoteStateStore(dir);
        store.save("v1", snapshot("v1", 1, 1, stale, 1), Map.of());
        assertThat(store.load("v1").orElseThrow().stale())
                .isEqualTo(stale);
    }

    @ParameterizedTest(name = "rate {0}")
    @ValueSource(doubles = {0.1, 0.3, 0.5, 0.9, 1.0})
    void rlLearningRates(double rate) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                rate, 0.0, 10.0);
        autonomy.record(Action.RELAX, 1.0);
        assertThat(autonomy.q(Action.RELAX)).isEqualTo(rate);
    }

    @ParameterizedTest(name = "epsilon {0}")
    @ValueSource(doubles = {0.0, 0.2, 0.5, 0.8, 1.0})
    void rlEpsilons(double epsilon) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                0.1, epsilon, 10.0);
        for (int i = 0; i < 100; i++) {
            assertThat(autonomy.chooseAction()).isNotNull();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void rlRounds(int rounds) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                0.1, 0.0, 10.0);
        for (int i = 0; i < rounds; i++) {
            autonomy.record(Action.RELAX, 1.0);
        }
        assertThat(autonomy.q(Action.RELAX))
                .isBetween(0.0, 1.0);
    }

    @ParameterizedTest(name = "bound {0}")
    @ValueSource(doubles = {0.5, 1.0, 5.0, 10.0, 100.0})
    void rlQBounds(double bound) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                1.0, 0.0, bound);
        autonomy.record(Action.RELAX, 10_000.0);
        assertThat(autonomy.q(Action.RELAX)).isEqualTo(bound);
    }

    @ParameterizedTest(name = "reward {0}")
    @ValueSource(doubles = {-10.0, -1.0, 0.0, 1.0, 10.0})
    void rlRewards(double reward) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                0.5, 0.0, 100.0);
        autonomy.record(Action.TIGHTEN, reward);
        assertThat(autonomy.q(Action.TIGHTEN)).isEqualTo(
                reward / 2);
    }

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(longs = {0, 100, 1000, 60_000, 3_600_000})
    void lifecycleTtls(long ttl) {
        MaterializedViewLifecycle lifecycle =
                new MaterializedViewLifecycle();
        RemoteSnapshot snapshot = snapshot("v1", 10, 2, false,
                1000);
        assertThat(lifecycle.expired(snapshot, ttl,
                1000 + ttl)).isFalse();
        assertThat(lifecycle.expired(snapshot, ttl,
                1000 + ttl + 1)).isTrue();
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void lifecycleSweepCounts(int count) {
        RemoteMaterializationManager manager = manager();
        for (int i = 0; i < count; i++) {
            manager.define(new RemoteDefinition("v" + i, "gcp-us",
                    "aws-us", List.of(new CloudShard("d1",
                            "aws-us", "m")), Aggregate.SUM));
        }
        List<String> expired = new MaterializedViewLifecycle()
                .sweep(manager, 0,
                        System.currentTimeMillis() + 1);
        assertThat(expired).hasSize(count);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 5.0, 100.0, 1_000.0})
    void lifecycleArchiveValues(double value) {
        MaterializedViewLifecycle lifecycle =
                new MaterializedViewLifecycle();
        RemoteSnapshot snapshot = new RemoteSnapshot("v1",
                "gcp-us", value, 1, false, 1000);
        assertThat(lifecycle.restore(lifecycle.archive(
                snapshot, 2000)).value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"a", "secret", "key-123", "long-key-x"})
    void signedKeys(String keyValue) {
        byte[] key = keyValue.getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1);
        var signed = SignedAttestation.sign(
                chain.attestations().get(0), key);
        assertThat(new SignatureVerifier().verify(signed, key))
                .isTrue();
    }

    @ParameterizedTest(name = "index {0}")
    @ValueSource(ints = {0, 1, 5, 20, 100})
    void signedIndexes(int index) {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        String prev = index == 0 ? "" : "p";
        var attestation = new AttestationChain.Attestation(index,
                "GDPR", "v1", 0, prev,
                AttestationChain.hash(index, "GDPR", "v1", 0, prev),
                index);
        var signed = SignedAttestation.sign(attestation, key);
        assertThat(new SignatureVerifier().verify(signed, key))
                .isTrue();
    }

    @ParameterizedTest(name = "violations {0}")
    @ValueSource(ints = {0, 1, 5, 50, 500})
    void signedViolations(int violations) {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", violations, 1);
        var signed = SignedAttestation.sign(
                chain.attestations().get(0), key);
        assertThat(new SignatureVerifier().verify(signed, key))
                .isTrue();
    }

    @ParameterizedTest(name = "regulations {0}")
    @ValueSource(strings = {"GDPR", "SOC2", "PCI", "HIPAA", "SOX"})
    void signedRegulations(String regulation) {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append(regulation, "v1", 0, 1);
        var signed = SignedAttestation.sign(
                chain.attestations().get(0), key);
        assertThat(new SignatureVerifier().verify(signed, key))
                .isTrue();
    }

    @ParameterizedTest(name = "penalty {0}")
    @ValueSource(doubles = {0.0, 1.0, 2.0, 5.0, 10.0})
    void spotMigrationPenalties(double penalty) {
        SpotMigrationPlanner planner = new SpotMigrationPlanner(
                penalty);
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(
                        new SpotOption("aws-us", 1, 0.5, 100,
                                true),
                        new SpotOption("gcp-us", 5, 0.0, 100,
                                true)),
                new SpotTask("t1", "us", 10, false), policy());
        assertThat(plan).isPresent();
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20})
    void spotMigrationCandidates(int count) {
        SpotMigrationPlanner planner = new SpotMigrationPlanner();
        List<SpotOption> options = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(new SpotOption("c" + i, i + 1, 0.0,
                    100, true));
        }
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "c0", options,
                new SpotTask("t1", "default", 10, false),
                new DataResidencyPolicy(Map.of()));
        assertThat(plan.isPresent()).isEqualTo(count > 1);
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {0, 10, 50, 100, 200})
    void spotMigrationQuotas(long quota) {
        SpotMigrationPlanner planner = new SpotMigrationPlanner();
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(
                        new SpotOption("aws-us", 1, 0.0, 100,
                                true),
                        new SpotOption("gcp-us", 2, 0.0, 100,
                                true)),
                new SpotTask("t1", "us", quota, false), policy());
        assertThat(plan.isPresent()).isEqualTo(quota <= 100);
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {0, 1, 3, 5, 10})
    void riskScorePairs(int pairs) {
        IsolationPolicy policy = riskPolicy(20, false);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.allowPairs()).isEqualTo(pairs);
        assertThat(risk.score()).isLessThanOrEqualTo(100);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {2, 5, 10, 20, 50})
    void riskScoreTenants(int count) {
        IsolationPolicy policy = riskPolicy(count, true);
        policy.allow("t0", "t1");
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.privateExposure()).isTrue();
        assertThat(risk.score()).isEqualTo(30);
    }

    @ParameterizedTest(name = "exposure {0}")
    @ValueSource(ints = {1, 3, 5, 8, 10})
    void riskDashboardExposure(int pairs) {
        IsolationPolicy policy = riskPolicy(pairs + 2, false);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        long total = new RiskDashboard().exposureByTenant(policy)
                .values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(2L * pairs);
    }

    @ParameterizedTest(name = "score {0}")
    @ValueSource(ints = {0, 10, 20, 30, 100})
    void riskDashboardScores(int expected) {
        IsolationPolicy policy = riskPolicy(10, true);
        if (expected == 0) {
            assertThat(new RiskDashboard().scoreByTenant(policy)
                    .get("t0")).isZero();
        } else if (expected == 10) {
            policy.allow("t0", "t1");
            assertThat(new RiskDashboard().scoreByTenant(policy)
                    .get("t0")).isEqualTo(30);
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"10,2", "20,5", "50,10", "100,20", "200,50"})
    void riskScoreMix(int tenants, int pairs) {
        IsolationPolicy policy = riskPolicy(tenants, false);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.allowPairs()).isEqualTo(pairs);
    }

    @ParameterizedTest(name = "values {0}")
    @ValueSource(ints = {1, 5, 10, 50, 100})
    void stateStoreUpdateRounds(int rounds) {
        RemoteStateStore store = new RemoteStateStore(dir);
        for (int i = 0; i < rounds; i++) {
            store.save("v1", snapshot("v1", i, 1, false, i),
                    Map.of("k", (double) i));
        }
        assertThat(store.load("v1").orElseThrow().value())
                .isEqualTo(rounds - 1);
    }

    @ParameterizedTest(name = "stale rounds {0}")
    @ValueSource(ints = {1, 5, 10, 20, 50})
    void stateStoreStaleRounds(int rounds) {
        RemoteStateStore store = new RemoteStateStore(dir);
        for (int i = 0; i < rounds; i++) {
            store.save("v1", snapshot("v1", 1, 1,
                    i % 2 == 0, i), Map.of());
        }
        assertThat(store.load("v1")).isPresent();
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {1, 7, 42, 99, 1234})
    void rlSeeds(long seed) {
        ReinforcementAutonomy first = new ReinforcementAutonomy(
                0.1, 0.5, 10.0, new java.util.Random(seed));
        ReinforcementAutonomy second = new ReinforcementAutonomy(
                0.1, 0.5, 10.0, new java.util.Random(seed));
        for (int i = 0; i < 50; i++) {
            assertThat(first.chooseAction())
                    .isEqualTo(second.chooseAction());
        }
    }

    @ParameterizedTest(name = "weights {0}")
    @ValueSource(doubles = {0.1, 0.5, 1.0, 5.0, 10.0})
    void rlWeightEvolution(double reward) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                0.2, 0.0, 100.0);
        for (int i = 0; i < 50; i++) {
            autonomy.record(Action.RELAX, reward);
            autonomy.record(Action.TIGHTEN, -reward);
        }
        assertThat(autonomy.weights().get(Action.RELAX))
                .isGreaterThan(autonomy.weights()
                        .get(Action.TIGHTEN));
    }

    @ParameterizedTest(name = "archive {0}")
    @ValueSource(ints = {1, 5, 20, 50, 100})
    void lifecycleArchiveRounds(int rounds) {
        MaterializedViewLifecycle lifecycle =
                new MaterializedViewLifecycle();
        RemoteSnapshot snapshot = snapshot("v1", 10, 2, false, 1);
        for (int i = 0; i < rounds; i++) {
            snapshot = lifecycle.restore(
                    lifecycle.archive(snapshot, i));
        }
        assertThat(snapshot.value()).isEqualTo(10);
    }

    @ParameterizedTest(name = "sync {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void lifecycleSyncRefresh(int count) {
        RemoteMaterializationManager manager = manager();
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        for (int i = 0; i < count; i++) {
            manager.syncChange("v1",
                    new io.tieringkv.datamesh
                            .CdcMaterializedViewRefresher.CdcChange(
                            "k" + i,
                            io.tieringkv.datamesh
                                    .CdcMaterializedViewRefresher
                                    .ChangeType.INSERT, 1));
        }
        assertThat(manager.snapshot("v1").value())
                .isEqualTo(count);
    }

    @ParameterizedTest(name = "signatures {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void signedChainLengths(int length) {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < length; i++) {
            chain.append("GDPR", "v1", i % 3, i);
        }
        for (var attestation : chain.attestations()) {
            var signed = SignedAttestation.sign(attestation, key);
            assertThat(new SignatureVerifier().verify(signed, key))
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void signedRounds(int rounds) {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1);
        var attestation = chain.attestations().get(0);
        for (int i = 0; i < rounds; i++) {
            var signed = SignedAttestation.sign(attestation, key);
            assertThat(new SignatureVerifier().verify(signed, key))
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "migrations {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void spotMigrationRounds(int rounds) {
        SpotMigrationPlanner planner = new SpotMigrationPlanner();
        List<SpotOption> options = List.of(
                new SpotOption("aws-us", 2, 0.8, 100, true),
                new SpotOption("gcp-us", 4, 0.0, 100, true));
        for (int i = 0; i < rounds; i++) {
            Optional<MigrationPlan> plan = planner.plan(
                    "t" + i, "aws-us", options,
                    new SpotTask("t" + i, "us", 10, false),
                    policy());
            assertThat(plan.orElseThrow().toCloud())
                    .isEqualTo("gcp-us");
        }
    }

    @ParameterizedTest(name = "penalty {0}")
    @ValueSource(doubles = {0.0, 0.5, 2.0, 5.0, 20.0})
    void spotMigrationPenaltyRates(double penalty) {
        SpotMigrationPlanner planner = new SpotMigrationPlanner(
                penalty);
        Optional<MigrationPlan> plan = planner.plan(
                "t1", "aws-us",
                List.of(
                        new SpotOption("aws-us", 2, 0.5, 100,
                                true),
                        new SpotOption("gcp-us", 4, 0.0, 100,
                                true)),
                new SpotTask("t1", "us", 10, false), policy());
        assertThat(plan).isPresent();
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 3, 5, 8, 12})
    void riskScoreCaps(int pairs) {
        IsolationPolicy policy = riskPolicy(pairs + 2, true);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.score()).isLessThanOrEqualTo(100);
        assertThat(risk.privateExposure()).isTrue();
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {3, 6, 12, 25, 60})
    void riskDashboardTenantScales(int count) {
        IsolationPolicy policy = riskPolicy(count, false);
        for (int i = 0; i < count - 1; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        assertThat(new RiskDashboard().exposureByTenant(policy)
                .size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2,false", "10,5,true", "20,8,true",
            "50,15,false", "100,30,true"})
    void riskScoreMixMatrix(int tenants, int pairs,
                            boolean privateAll) {
        IsolationPolicy policy = riskPolicy(tenants, privateAll);
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        var risk = new PolicyRiskScorer().score(policy);
        assertThat(risk.allowPairs()).isEqualTo(pairs);
        assertThat(risk.privateExposure()).isEqualTo(privateAll);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 10, 25, 100})
    void lifecycleArchiveViews(int count) {
        RemoteMaterializationManager manager = manager();
        MaterializedViewLifecycle lifecycle =
                new MaterializedViewLifecycle();
        for (int i = 0; i < count; i++) {
            manager.define(new RemoteDefinition("v" + i,
                    "gcp-us", "aws-us",
                    List.of(new CloudShard("d1", "aws-us", "m")),
                    Aggregate.SUM));
            lifecycle.archive(manager.snapshot("v" + i), i);
        }
        assertThat(manager.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 10, 50, 100, 500})
    void stateStoreKeyVolumes(int count) {
        RemoteStateStore store = new RemoteStateStore(dir);
        Map<String, Double> keys = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            keys.put("k" + i, i * 1.0);
        }
        store.save("v1", snapshot("v1", count, count, false, 1),
                keys);
        assertThat(store.load("v1").orElseThrow().keys())
                .hasSize(count);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void rlMixedActionRounds(int rounds) {
        ReinforcementAutonomy autonomy = new ReinforcementAutonomy(
                0.1, 0.3, 10.0);
        for (int i = 0; i < rounds; i++) {
            autonomy.record(autonomy.chooseAction(),
                    i % 2 == 0 ? 1.0 : -1.0);
        }
        assertThat(autonomy.weights().values().stream()
                .mapToDouble(Double::doubleValue).sum())
                .isCloseTo(1.0,
                        org.assertj.core.data.Offset.offset(1e-9));
    }

    private static RemoteSnapshot snapshot(String viewId,
                                           double value, long count,
                                           boolean stale,
                                           long refreshedAt) {
        return new RemoteSnapshot(viewId, "gcp-us", value, count,
                stale, refreshedAt);
    }

    private static RemoteMaterializationManager manager() {
        return new RemoteMaterializationManager(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")));
    }

    private static DataResidencyPolicy policy() {
        return new DataResidencyPolicy(Map.of(
                "aws-us", "us", "gcp-us", "us"));
    }

    private static IsolationPolicy riskPolicy(int count,
                                              boolean privateAll) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i,
                    privateAll));
        }
        return policy;
    }
}
