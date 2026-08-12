package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.SelfLearningFence;
import io.tieringkv.capacity.ai.SelfLearningFence.Bounds;
import io.tieringkv.capacity.ai.SelfLearningFence.Params;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.ChangeType;
import io.tieringkv.datamesh.CloudFederatedExecutor;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.MaterializedViewManager;
import io.tieringkv.datamesh.MaterializedViewManager.Definition;
import io.tieringkv.observability.cost.CloudCostScheduler;
import io.tieringkv.observability.cost.CloudCostScheduler.CloudOption;
import io.tieringkv.observability.cost.CloudCostScheduler.ScheduleTask;
import io.tieringkv.operations.slo.SloBudgetPlanner;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.PolicyCompiler;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

/** Phase 36 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase36BenchmarkTest {

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10000})
    void selfLearningFenceThroughput(int records) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5),
                new Bounds(1, 20, 1, 10, 1, 8), 1, 1, 2, 2);
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            if (i % 5 == 0) {
                fence.recordFailure("x");
            } else {
                fence.recordSuccess();
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE36-BENCH SELF-FENCE %d -> %d ops/s%n",
                records, records * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "changes {0}")
    @ValueSource(ints = {1000, 10000})
    void cdcRefreshThroughput(int changes) {
        MaterializedViewManager manager = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM, 60_000));
        CdcMaterializedViewRefresher refresher =
                new CdcMaterializedViewRefresher();
        long start = System.nanoTime();
        for (int i = 0; i < changes; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k" + (i % 100),
                            i % 3 == 0 ? ChangeType.DELETE
                                    : ChangeType.INSERT, 1));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE36-BENCH CDC-MV %d -> %d ops/s%n",
                changes, changes * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "attestations {0}")
    @ValueSource(ints = {1000, 10000})
    void attestationChainThroughput(int count) {
        AttestationChain chain = new AttestationChain();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            chain.append("GDPR", "v1", i % 5, i);
        }
        org.junit.jupiter.api.Assertions.assertTrue(chain.verify());
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE36-BENCH ATTEST %d -> %d ops/s%n",
                count, count * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "tasks {0}")
    @ValueSource(ints = {1000, 10000})
    void cloudSchedulingThroughput(int tasks) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        DataResidencyPolicy policy = new DataResidencyPolicy(
                Map.of("aws-us", "us", "gcp-us", "us"));
        List<CloudOption> options = List.of(
                new CloudOption("aws-us", 5, 1000, true),
                new CloudOption("gcp-us", 3, 1000, true));
        long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            scheduler.schedule(new ScheduleTask("t" + i, "us",
                    10, false), options, policy);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE36-BENCH CLOUD-SCHED %d -> %d ops/s%n",
                tasks, tasks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1000, 5000})
    void policyCompilerThroughput(int rules) {
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
        PolicyCompiler compiler = new PolicyCompiler();
        long start = System.nanoTime();
        compiler.apply(policy, dsl.toString());
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE36-BENCH POLICY %d -> %d rules/s%n",
                rules, rules * 1_000L / elapsedMs);
    }

    @Test
    void sloBudgetPlanningLatency() {
        SloBudgetPlanner planner = new SloBudgetPlanner();
        long start = System.nanoTime();
        for (int i = 0; i < 100_000; i++) {
            planner.plan((i % 100) / 100.0, 0.9, 10, 50);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE36-BENCH SLO-BUDGET %d ms%n",
                elapsedMs);
    }

}
