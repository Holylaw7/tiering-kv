package io.tieringkv.platform;

import io.tieringkv.capacity.ai.SelfLearningFence;
import io.tieringkv.capacity.ai.SelfLearningFence.Bounds;
import io.tieringkv.capacity.ai.SelfLearningFence.Params;
import io.tieringkv.compliance.AttestationChain;
import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
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
import io.tieringkv.operations.slo.SloBudgetPlanner.Action;
import io.tieringkv.operations.slo.SloBudgetPlanner.BudgetPlan;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import io.tieringkv.security.network.NetworkPolicyDsl;
import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;
import io.tieringkv.security.network.PolicyCompiler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 36 参数化边缘矩阵：自学习/CDC/证明/调度/策略/SLO。 */
class Phase36EdgeMatrixTest {

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void selfFenceSuccessThresholds(int threshold) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1,
                threshold, 2);
        for (int i = 0; i < threshold; i++) {
            fence.recordSuccess();
        }
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(11);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void selfFenceFailureThresholds(int threshold) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1, 2,
                threshold);
        for (int i = 0; i < threshold; i++) {
            fence.recordFailure("x");
        }
        assertThat(fence.params().maxActionsPerDay()).isEqualTo(9);
    }

    @ParameterizedTest(name = "step {0}")
    @ValueSource(ints = {1, 2, 3, 5})
    void selfFenceRelaxSteps(int step) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), step, 1, 1, 1);
        fence.recordSuccess();
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(10 + step);
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {5, 10, 20, 50})
    void selfFenceUpperBounds(int maxActions) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5),
                new Bounds(1, maxActions, 1, 10, 1, 8),
                1, 1, 1, 1);
        for (int i = 0; i < 100; i++) {
            fence.recordSuccess();
        }
        assertThat(fence.params().maxActionsPerDay())
                .isLessThanOrEqualTo(maxActions);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void selfFenceAlternatingRounds(int rounds) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(10, 5, 5), bounds(), 1, 1, 1, 1);
        for (int i = 0; i < rounds; i++) {
            if (i % 2 == 0) {
                fence.recordSuccess();
            } else {
                fence.recordFailure("x");
            }
        }
        assertThat(fence.audit()).isNotEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void cdcInsertVolumes(int count) {
        CdcMaterializedViewRefresher refresher = refresher();
        MaterializedViewManager manager = manager();
        for (int i = 0; i < count; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k" + i, ChangeType.INSERT, 1));
        }
        assertThat(manager.query("v1").value()).isEqualTo(count);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void cdcAggregates(String aggregate) {
        CdcMaterializedViewRefresher refresher = refresher();
        MaterializedViewManager manager = manager();
        refresher.apply(manager, "v1",
                Aggregate.valueOf(aggregate),
                new CdcChange("a", ChangeType.INSERT, 4));
        refresher.apply(manager, "v1",
                Aggregate.valueOf(aggregate),
                new CdcChange("b", ChangeType.INSERT, 8));
        assertThat(manager.query("v1").stale()).isFalse();
        assertThat(manager.query("v1").count()).isPositive();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 3, 10, 50})
    void cdcInsertUpdateDeleteRounds(int rounds) {
        CdcMaterializedViewRefresher refresher = refresher();
        MaterializedViewManager manager = manager();
        for (int i = 0; i < rounds; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.INSERT, i));
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.UPDATE, i + 1));
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.DELETE, 0));
        }
        assertThat(manager.query("v1").value()).isZero();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 10, 50, 100})
    void cdcKeyCounts(int count) {
        CdcMaterializedViewRefresher refresher = refresher();
        MaterializedViewManager manager = manager();
        for (int i = 0; i < count; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k" + i, ChangeType.INSERT, 1));
        }
        assertThat(refresher.trackedKeys("v1")).isEqualTo(count);
    }

    @ParameterizedTest(name = "length {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void attestationChainLengths(int length) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < length; i++) {
            chain.append("GDPR", "v" + (i % 3), i % 4, i);
        }
        assertThat(chain.size()).isEqualTo(length);
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "violations {0}")
    @ValueSource(ints = {0, 1, 5, 100})
    void attestationViolationCounts(int violations) {
        AttestationChain chain = new AttestationChain();
        chain.append("GDPR", "v1", violations, 1000);
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "regulation {0}")
    @ValueSource(strings = {"GDPR", "SOC2", "PCI-DSS", "HIPAA"})
    void attestationRegulations(String regulation) {
        AttestationChain chain = new AttestationChain();
        chain.append(regulation, "v1", 0, 1000);
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "versions {0}")
    @ValueSource(ints = {1, 3, 10, 20})
    void attestationVersionCounts(int count) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < count; i++) {
            chain.append("GDPR", "v" + i, 0, i);
        }
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.1, 1.0, 5.0, 10.0})
    void cloudSchedulerPrices(double price) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        var decision = scheduler.schedule(
                new ScheduleTask("t", "us", 10, false),
                List.of(new CloudOption("aws-us", price, 100, true),
                        new CloudOption("gcp-us", price * 2, 100,
                                true)),
                policy());
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo("aws-us");
        assertThat(decision.orElseThrow().pricePerUnit())
                .isEqualTo(price);
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {0, 10, 50, 100})
    void cloudSchedulerQuotas(long quota) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        var decision = scheduler.schedule(
                new ScheduleTask("t", "us", quota, false),
                List.of(new CloudOption("aws-us", 1, 100, true)),
                policy());
        assertThat(decision).isPresent();
    }

    @ParameterizedTest(name = "candidates {0}")
    @ValueSource(ints = {1, 2, 5, 10})
    void cloudSchedulerCandidateCounts(int count) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        List<CloudOption> options = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            options.add(new CloudOption("c" + i, i + 1, 100, true));
        }
        var decision = scheduler.schedule(
                new ScheduleTask("t", "default", 10, false),
                options, new DataResidencyPolicy(Map.of()));
        assertThat(decision.orElseThrow().pricePerUnit())
                .isEqualTo(1);
    }

    @ParameterizedTest(name = "slo {0}")
    @ValueSource(booleans = {true, false})
    void cloudSchedulerSloRequirement(boolean slo) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        var decision = scheduler.schedule(
                new ScheduleTask("t", "us", 10, slo),
                List.of(new CloudOption("aws-us", 1, 100, !slo),
                        new CloudOption("gcp-us", 2, 100, true)),
                policy());
        assertThat(decision).isPresent();
        assertThat(decision.orElseThrow().cloud())
                .isEqualTo(slo ? "gcp-us" : "aws-us");
    }

    @ParameterizedTest(name = "rules {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void policyDslRuleCounts(int count) {
        StringBuilder dsl = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dsl.append("allow: t").append(i).append(" -> t")
                    .append(i + 1).append('\n');
        }
        assertThat(NetworkPolicyDsl.parse(dsl.toString()))
                .hasSize(count);
    }

    @ParameterizedTest(name = "action {0}")
    @ValueSource(strings = {"allow", "deny", "allow", "deny"})
    void policyDslActions(String action) {
        List<PolicyRule> rules = NetworkPolicyDsl.parse(
                action + ": t1 -> t2");
        assertThat(rules.get(0).action()).isEqualTo(action);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {2, 5, 10, 20})
    void policyCompilerTenantCounts(int count) {
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

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void policyCompilerIdempotentRounds(int rounds) {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc", "subnet", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc", "subnet", true));
        PolicyCompiler compiler = new PolicyCompiler();
        for (int i = 0; i < rounds; i++) {
            compiler.applyIdempotent(policy, "allow: t1 -> t2");
        }
        assertThat(policy.whitelistEntries()).hasSize(1);
    }

    @ParameterizedTest(name = "comment {0}")
    @ValueSource(strings = {"# c\nallow: a -> b",
            "allow: a -> b\n# tail"})
    void policyDslComments(String dsl) {
        assertThat(NetworkPolicyDsl.parse(dsl)).isNotEmpty();
    }

    @ParameterizedTest(name = "comment only {0}")
    @ValueSource(strings = {"# only", "  \n# x\n"})
    void policyDslCommentOnly(String dsl) {
        assertThat(NetworkPolicyDsl.parse(dsl)).isEmpty();
    }

    @ParameterizedTest(name = "compliance {0}")
    @ValueSource(doubles = {0.0, 0.3, 0.5, 0.8, 0.95, 1.0})
    void sloBudgetComplianceMatrix(double compliance) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                compliance, 0.9, 10, 50);
        assertThat(plan.suggestedNodes()).isBetween(10, 50);
        if (compliance >= 0.9) {
            assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        } else {
            assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
        }
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(doubles = {0.5, 0.8, 0.95, 0.99})
    void sloBudgetTargets(double target) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                target, target, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 10, 50})
    void sloBudgetNodeCounts(int nodes) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                0.5, 0.9, nodes, nodes * 3);
        assertThat(plan.suggestedNodes()).isGreaterThan(nodes);
    }

    @ParameterizedTest(name = "factor {0}")
    @ValueSource(doubles = {1.0, 1.5, 2.0, 4.0})
    void sloBudgetHeadroomFactors(double factor) {
        BudgetPlan plan = new SloBudgetPlanner(factor).plan(
                0.5, 0.9, 10, 100);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @ParameterizedTest(name = "deficit {0}")
    @CsvSource({"0.85,0.9", "0.8,0.9", "0.7,0.9", "0.5,0.9"})
    void sloBudgetDeficitMatrix(double compliance, double target) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                compliance, target, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.SCALE_UP);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100})
    void sloBudgetRounds(int rounds) {
        SloBudgetPlanner planner = new SloBudgetPlanner();
        for (int i = 0; i < rounds; i++) {
            BudgetPlan plan = planner.plan(
                    (i % 10) / 10.0, 0.9, 10, 50);
            assertThat(plan.suggestedNodes()).isBetween(10, 50);
        }
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {10, 12, 20, 100})
    void sloBudgetMaxCaps(int maxNodes) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                0.1, 0.9, 10, maxNodes);
        assertThat(plan.suggestedNodes())
                .isLessThanOrEqualTo(maxNodes);
    }

    @ParameterizedTest(name = "tighten {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void selfFenceTightenSteps(int step) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(20, 10, 8), bounds(), 1, step, 1, 1);
        fence.recordFailure("x");
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(20 - step);
    }

    @ParameterizedTest(name = "min {0}")
    @ValueSource(ints = {1, 3, 5, 8, 10})
    void selfFenceLowerBounds(int minActions) {
        SelfLearningFence fence = new SelfLearningFence(
                new Params(minActions, 5, 5),
                new Bounds(minActions, 20, 1, 10, 1, 8),
                1, 1, 1, 1);
        for (int i = 0; i < 100; i++) {
            fence.recordFailure("x");
        }
        assertThat(fence.params().maxActionsPerDay())
                .isEqualTo(minActions);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void cdcUpdateVolumes(int count) {
        CdcMaterializedViewRefresher refresher = refresher();
        MaterializedViewManager manager = manager();
        for (int i = 0; i < count; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.UPDATE, i));
        }
        assertThat(manager.query("v1").value())
                .isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void cdcDeleteVolumes(int count) {
        CdcMaterializedViewRefresher refresher = refresher();
        MaterializedViewManager manager = manager();
        for (int i = 0; i < count; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k" + i, ChangeType.INSERT, 1));
        }
        for (int i = 0; i < count; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k" + i, ChangeType.DELETE, 0));
        }
        assertThat(manager.query("v1").value()).isZero();
        assertThat(refresher.trackedKeys("v1")).isZero();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void attestationAppendRounds(int rounds) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < rounds; i++) {
            chain.append("GDPR", "v1", i % 3, i);
        }
        assertThat(chain.size()).isEqualTo(rounds);
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "regulations {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void attestationMixedRegulations(int count) {
        AttestationChain chain = new AttestationChain();
        for (int i = 0; i < count; i++) {
            chain.append("R" + i, "v1", 0, i);
        }
        assertThat(chain.verify()).isTrue();
    }

    @ParameterizedTest(name = "residency {0}")
    @ValueSource(strings = {"us", "eu", "cn", "default", "other"})
    void cloudSchedulerResidencyMatrix(String residency) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        var decision = scheduler.schedule(
                new ScheduleTask("t", residency, 10, false),
                List.of(new CloudOption("aws-" + residency, 1,
                        100, true)),
                new DataResidencyPolicy(Map.of(
                        "aws-" + residency, residency)));
        assertThat(decision).isPresent();
    }

    @ParameterizedTest(name = "slo {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void cloudSchedulerSloMatrix(int sloCount) {
        CloudCostScheduler scheduler = new CloudCostScheduler();
        boolean required = sloCount % 2 == 1;
        var decision = scheduler.schedule(
                new ScheduleTask("t", "us", 10, required),
                List.of(new CloudOption("aws-us", 1, 100,
                                !required),
                        new CloudOption("gcp-us", 2, 100, true)),
                policy());
        assertThat(decision).isPresent();
    }

    @ParameterizedTest(name = "rule {0}")
    @ValueSource(strings = {"open: a -> b", "allow: a b",
            "allow -> b", "deny: a ->", "allow: -> b"})
    void policyDslMalformedRejected(String dsl) {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> NetworkPolicyDsl.parse(dsl));
    }

    @ParameterizedTest(name = "action {0}")
    @ValueSource(strings = {"allow: t1 -> t2",
            "deny: t1 -> t2", "allow: t2 -> t3",
            "deny: t2 -> t3", "allow: t3 -> t1"})
    void policyCompilerActionMatrix(String rule) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 1; i <= 3; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        new PolicyCompiler().apply(policy, rule);
        if (rule.startsWith("allow")) {
            assertThat(policy.whitelistEntries()).hasSize(1);
        } else {
            assertThat(policy.whitelistEntries()).isEmpty();
        }
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(ints = {10, 11, 15, 20, 100})
    void sloBudgetCapMatrix(int maxNodes) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                0.0, 0.9, 10, maxNodes);
        assertThat(plan.suggestedNodes())
                .isEqualTo(Math.min(maxNodes, 30));
    }

    @ParameterizedTest(name = "compliance {0}")
    @ValueSource(doubles = {0.9, 0.91, 0.95, 0.99, 1.0})
    void sloBudgetMaintainMatrix(double compliance) {
        BudgetPlan plan = new SloBudgetPlanner().plan(
                compliance, 0.9, 10, 50);
        assertThat(plan.action()).isEqualTo(Action.MAINTAIN);
        assertThat(plan.suggestedNodes()).isEqualTo(10);
    }

    private static SelfLearningFence.Bounds bounds() {
        return new Bounds(1, 20, 1, 10, 1, 8);
    }

    private static DataResidencyPolicy policy() {
        return new DataResidencyPolicy(Map.of(
                "aws-us", "us", "gcp-us", "us"));
    }

    private static MaterializedViewManager manager() {
        MaterializedViewManager manager = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM, 60_000));
        return manager;
    }

    private static CdcMaterializedViewRefresher refresher() {
        return new CdcMaterializedViewRefresher();
    }
}
