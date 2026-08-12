package io.tieringkv.platform;

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
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.observability.cost.CloudCostScheduler;
import io.tieringkv.observability.cost.CloudCostScheduler.CloudOption;
import io.tieringkv.observability.cost.CloudCostScheduler.ScheduleTask;
import io.tieringkv.operations.slo.SloBudgetPlanner;
import io.tieringkv.operations.slo.SloBudgetPlanner.Action;
import io.tieringkv.operations.slo.SloBudgetPlanner.BudgetPlan;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.PolicyCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 36 生产门禁（JVM 级）：自学习/增量物化/证明/调度/策略/SLO。 */
class Phase36ProductionGateTest {

    @Test
    void selfLearningFenceRelaxTightenGate() {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5),
                new Bounds(1, 20, 1, 10, 1, 8), 1, 1, 2, 2);
        fence.recordSuccess();
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(11);
        fence.recordFailure("x");
        fence.recordFailure("x");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(10);
    }

    @Test
    void selfLearningFenceCircuitGate() {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5),
                new Bounds(1, 20, 1, 10, 1, 8), 1, 1, 2, 2);
        fence.recordRollback("migration failed");
        assertThat(fence.circuitOpen()).isTrue();
        fence.resetCircuit();
        assertThat(fence.circuitOpen()).isFalse();
    }

    @Test
    void cdcIncrementalGate() {
        MaterializedViewManager manager = viewManager();
        CdcMaterializedViewRefresher refresher =
                new CdcMaterializedViewRefresher();
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("a", ChangeType.INSERT, 10));
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("b", ChangeType.INSERT, 5));
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("a", ChangeType.DELETE, 0));
        assertThat(manager.query("v1").value()).isEqualTo(5);
        assertThat(manager.isStale("v1")).isFalse();
    }

    @Test
    void cdcFallbackFullRefreshGate() {
        MaterializedViewManager manager = viewManager();
        CdcMaterializedViewRefresher refresher =
                new CdcMaterializedViewRefresher();
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("a", ChangeType.INSERT, 10));
        refresher.refreshFull(manager, "v1", "aws-us",
                shard -> new io.tieringkv.datamesh
                        .CloudFederatedExecutor.CloudResult(
                        shard.domainId(), shard.cloud(), 42, 1));
        assertThat(manager.query("v1").value()).isEqualTo(42);
        assertThat(refresher.trackedKeys("v1")).isZero();
    }

    @Test
    void attestationTamperGate() {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", 0, 1000);
        var original = chain.append("GDPR", "v1", 1, 2000);
        var tampered = new AttestationChain.Attestation(
                original.index(), original.regulation(),
                original.versionId(), 99, original.prevHash(),
                original.hash(), original.timestampMillis());
        AttestationChain broken = new AttestationChain(
                List.of(chain.attestations().get(0), tampered));
        assertThat(broken.verify()).isFalse();
        assertThat(chain.verify()).isTrue();
    }

    @Test
    void cloudSchedulerConstraintsGate() {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        var decision = scheduler.schedule(
                new ScheduleTask("t1", "us", 10, true),
                List.of(
                        new CloudOption("aws-us", 1, 100, false),
                        new CloudOption("gcp-us", 2, 100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")));
        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void cloudSchedulerSovereigntyGate() {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        assertThat(scheduler.schedule(
                new ScheduleTask("t1", "us", 10, false),
                List.of(new CloudOption("aws-eu", 1, 100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-eu", "eu")))).isEmpty();
    }

    @Test
    void policyDslCompileGate() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-2", "subnet-2", true));
        new PolicyCompiler().apply(policy, "allow: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "t2")).isTrue();
        new PolicyCompiler().apply(policy, "deny: t1 -> t2");
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void policyDslInvalidRejectedGate() {
        assertThatThrownBy(() -> new PolicyCompiler().apply(
                new IsolationPolicy(), "open: t1 -> t2"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sloBudgetScaleUpGate() {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                0.7, 0.9, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        assertThat(plan.suggestedNodes()).isGreaterThan(10);
    }

    @Test
    void sloBudgetMaintainGate() {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                0.95, 0.9, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        assertThat(plan.suggestedNodes()).isEqualTo(10);
    }

    @ParameterizedTest(name = "tenant {0}")
    @ValueSource(ints = {5, 20})
    void parameterizedPolicyCompileCounts(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count - 1; i++) {
            dsl.append("allow: t").append(i).append(" -> t")
                    .append(i + 1).append('\n');
        }
        new PolicyCompiler().apply(policy, dsl.toString());
        assertThat(policy.whitelistEntries()).hasSize(count - 1);
    }

    @ParameterizedTest(name = "compliance {0}")
    @ValueSource(doubles = {0.0, 0.5, 0.9, 1.0})
    void parameterizedSloBudget(double compliance) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                compliance, 0.9, 10, 50);
        assertThat(plan.suggestedNodes()).isBetween(10, 50);
    }

    @Test
    void attestationChainValidGate() {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < 100; i++) {
            chain.append("GDPR", "v1", i % 3, i);
        }
        assertThat(chain.verify()).isTrue();
    }

    @Test
    void selfLearningFenceBoundsGate() {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(1, 1, 1),
                new Bounds(1, 20, 1, 10, 1, 8), 1, 1, 1, 1);
        fence.recordFailure("x");
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(1);
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(2);
    }

    @Test
    void cdcConcurrentGate() throws Exception {
        MaterializedViewManager manager = viewManager();
        CdcMaterializedViewRefresher refresher =
                new CdcMaterializedViewRefresher();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    refresher.apply(manager, "v1", Aggregate.SUM,
                            new CdcChange("k" + (i % 10),
                                    ChangeType.INSERT, 1));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(manager.query("v1").value()).isEqualTo(10);
        assertThat(manager.isStale("v1")).isFalse();
    }

    private static MaterializedViewManager viewManager() {
        MaterializedViewManager manager = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM, 60_000));
        return manager;
    }
}
