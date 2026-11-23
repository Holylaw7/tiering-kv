package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.MultiObjectiveFence;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Bounds;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Feedback;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Params;
import io.tieringkv.capacity.ai.MultiObjectiveFence.Weights;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.AttestationExporter;
import io.tieringkv.compliance.AttestationVerifier;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.ChangeType;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.RemoteMaterializationManager;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteDefinition;
import io.tieringkv.observability.cost.SpotAwareScheduler;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotOption;
import io.tieringkv.observability.cost.SpotAwareScheduler.SpotTask;
import io.tieringkv.operations.slo.MultiSloNegotiator;
import io.tieringkv.operations.slo.MultiSloNegotiator.SloInput;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.NetworkPolicyAudit;
import io.tieringkv.security.network.PolicyAuditView;
import io.tieringkv.security.network.PolicyCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

/** Phase 37 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase37BenchmarkTest {

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10000})
    void multiObjectiveFenceThroughput(int records) {
        MultiObjectiveFence fence = new MultiObjectiveFence(
                new Params(10, 5, 5),
                new Bounds(1, 20, 1, 10, 1, 8),
                new Weights(1, 1, 1), 0.8, 0.2, 1, 1);
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            fence.record(new Feedback(i % 5 == 0 ? 0.0 : 1.0,
                    i % 5 == 1 ? 1.0 : 0.0,
                    i % 5 == 2 ? 1.0 : 0.0));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH MO-FENCE %d -> %d ops/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "changes {0}")
    @ValueSource(ints = {1000, 10000})
    void remoteMaterializationThroughput(int changes) {
        RemoteMaterializationManager manager =
                new RemoteMaterializationManager(
                        new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us")));
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        long start = System.nanoTime();
        for (int i = 0; i < changes; i++) {
            manager.syncChange("v1", new CdcChange(
                    "k" + (i % 100), ChangeType.INSERT, 1));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH REMOTE-MV %d -> %d ops/s%n",
                changes, changes * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "exports {0}")
    @ValueSource(ints = {1000, 5000})
    void attestationExportThroughput(int exports) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < 20; i++) {
            chain.append("GDPR", "v1", i % 4, i);
        }
        AttestationExporter exporter = new AttestationExporter();
        AttestationVerifier verifier = new AttestationVerifier();
        String json = exporter.toJson(chain);
        long start = System.nanoTime();
        for (int i = 0; i < exports; i++) {
            verifier.verify(exporter.fromJson(json));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH ATTEST-EXPORT %d -> %d ops/s%n",
                exports, exports * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "tasks {0}")
    @ValueSource(ints = {1000, 10000})
    void spotSchedulingThroughput(int tasks) {
        SpotAwareScheduler scheduler = new SpotAwareScheduler();
        DataResidencyPolicy policy = new DataResidencyPolicy(
                Map.of("aws-us", "us", "gcp-us", "us"));
        List<SpotOption> options = List.of(
                new SpotOption("aws-us", 2, 0.8, 1000, true),
                new SpotOption("gcp-us", 4, 0.0, 1000, true));
        long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            scheduler.schedule(new SpotTask("t" + i, "us", 10,
                    false), options, policy);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH SPOT %d -> %d ops/s%n",
                tasks, tasks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1000, 5000})
    void policyAuditThroughput(int rules) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i <= 10; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < rules; i++) {
            dsl.append("allow: t").append(i % 10).append(" -> t")
                    .append((i + 1) % 10).append('\n');
        }
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        PolicyCompiler compiler = new PolicyCompiler();
        long start = System.nanoTime();
        compiler.apply(policy, dsl.toString(), audit);
        org.junit.jupiter.api.Assertions.assertEquals(rules,
                audit.size());
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH POLICY-AUDIT %d -> %d rules/s%n",
                rules, rules * 1_000L / elapsedMs);
    }

    @Test
    void multiSloNegotiationLatency() {
        MultiSloNegotiator negotiator = new MultiSloNegotiator();
        List<SloInput> inputs = List.of(
                new SloInput("latency", 0.8, 0.9, 1),
                new SloInput("throughput", 0.7, 0.9, 1),
                new SloInput("availability", 0.95, 0.9, 1));
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            negotiator.negotiate(inputs, 10, 50);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH MULTI-SLO %d ms%n",
                elapsedMs);
    }

    @Test
    void policyAuditViewLatency() {
        NetworkPolicyAudit audit = new NetworkPolicyAudit();
        PolicyAuditView view = new PolicyAuditView();
        for (int i = 0; i < 1000; i++) {
            audit.record("src",
                    new io.tieringkv.security.network
                            .NetworkPolicyDsl.PolicyRule(
                            i % 2 == 0 ? "allow" : "deny",
                            "t" + (i % 100), "t" + ((i + 1) % 100)),
                    i);
        }
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            view.byTenant(audit);
            view.byAction(audit);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE37-BENCH AUDIT-VIEW %d ms%n",
                elapsedMs);
    }

}
