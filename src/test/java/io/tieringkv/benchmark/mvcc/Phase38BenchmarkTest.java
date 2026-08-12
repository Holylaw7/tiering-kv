package io.tieringkv.benchmark.mvcc;

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
import io.tieringkv.observability.cost.SpotMigrationPlanner;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
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

/** Phase 38 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase38BenchmarkTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10000})
    void reinforcementAutonomyThroughput(int records) {
        ReinforcementAutonomy autonomy =
                new ReinforcementAutonomy(0.1, 0.0, 10.0);
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            Action action = autonomy.chooseAction();
            autonomy.record(action, i % 2 == 0 ? 1.0 : -1.0);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE38-BENCH RL-AUTO %d -> %d ops/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "saves {0}")
    @ValueSource(ints = {1000, 5000})
    void remoteStateStoreThroughput(int saves) {
        RemoteStateStore store = new RemoteStateStore(dir);
        long start = System.nanoTime();
        for (int i = 0; i < saves; i++) {
            store.save("v" + (i % 50),
                    new RemoteMaterializationManager.RemoteSnapshot(
                            "v" + (i % 50), "gcp-us", i, 1,
                            false, i),
                    Map.of("k", (double) i));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE38-BENCH STATE-STORE %d -> %d ops/s%n",
                saves, saves * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "signs {0}")
    @ValueSource(ints = {1000, 10000})
    void signedAttestationThroughput(int signs) {
        byte[] key = "bench-key".getBytes(StandardCharsets.UTF_8);
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1);
        var attestation = chain.attestations().get(0);
        SignatureVerifier verifier = new SignatureVerifier();
        long start = System.nanoTime();
        for (int i = 0; i < signs; i++) {
            var signed = SignedAttestation.sign(attestation, key);
            org.junit.jupiter.api.Assertions.assertTrue(
                    verifier.verify(signed, key));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE38-BENCH SIGNED %d -> %d ops/s%n",
                signs, signs * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "plans {0}")
    @ValueSource(ints = {1000, 10000})
    void spotMigrationThroughput(int plans) {
        SpotMigrationPlanner planner = new SpotMigrationPlanner();
        List<SpotOption> options = List.of(
                new SpotOption("aws-us", 2, 0.8, 1000, true),
                new SpotOption("gcp-us", 4, 0.0, 1000, true));
        DataResidencyPolicy policy = new DataResidencyPolicy(
                Map.of("aws-us", "us", "gcp-us", "us"));
        long start = System.nanoTime();
        for (int i = 0; i < plans; i++) {
            planner.plan("t" + i, "aws-us", options,
                    new SpotTask("t" + i, "us", 10, false), policy);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE38-BENCH SPOT-MIGRATE %d -> %d ops/s%n",
                plans, plans * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {100, 1000})
    void lifecycleSweepThroughput(int views) {
        RemoteMaterializationManager manager =
                new RemoteMaterializationManager(
                        new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us")));
        for (int i = 0; i < views; i++) {
            manager.define(new RemoteDefinition("v" + i, "gcp-us",
                    "aws-us", List.of(new CloudShard("d1",
                            "aws-us", "m")), Aggregate.SUM));
        }
        MaterializedViewLifecycle lifecycle =
                new MaterializedViewLifecycle();
        long start = System.nanoTime();
        lifecycle.sweep(manager, 60_000,
                System.currentTimeMillis() + 1000);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE38-BENCH LIFECYCLE %d -> %d views/s%n",
                views, views * 1_000L / elapsedMs);
    }

    @Test
    void riskScoringLatency() {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < 100; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", i % 2 == 0));
        }
        for (int i = 0; i < 50; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        PolicyRiskScorer scorer = new PolicyRiskScorer();
        RiskDashboard dashboard = new RiskDashboard();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            scorer.score(policy);
            dashboard.scoreByTenant(policy);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE38-BENCH RISK %d ms%n",
                elapsedMs);
    }
}
