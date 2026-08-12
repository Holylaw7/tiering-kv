package io.tieringkv.platform;

import io.tieringkv.capacity.ai.AutonomousCapacityController;
import io.tieringkv.capacity.ai.GlobalAutonomyOrchestrator;
import io.tieringkv.compliance.AuditExporter;
import io.tieringkv.compliance.ContinuousAuditPipeline;
import io.tieringkv.compliance.RegulationMapper.Control;
import io.tieringkv.compliance.RegulationVersion;
import io.tieringkv.compliance.RegulationVersionStore;
import io.tieringkv.datamesh.CloudFederatedExecutor;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.MaterializedViewManager;
import io.tieringkv.datamesh.MaterializedViewManager.Definition;
import io.tieringkv.gateway.AutonomousTrafficController;
import io.tieringkv.gateway.GlobalTrafficAutonomy;
import io.tieringkv.gateway.RegionQuota;
import io.tieringkv.observability.cost.WorkloadCostOptimizer;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.WorkloadProfile;
import io.tieringkv.operations.slo.SloManager;
import io.tieringkv.operations.slo.SloManager.SloDefinition;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 35 参数化边缘矩阵：自治/物化/合规/成本/隔离/SLO。 */
class Phase35EdgeMatrixTest {

    @ParameterizedTest(name = "budget {0}")
    @ValueSource(ints = {1, 3, 10})
    void globalAutonomyBudgets(int budget) {
        GlobalAutonomyOrchestrator orchestrator = autonomy(budget, 10);
        int executed = 0;
        for (int i = 0; i < budget * 2; i++) {
            if (orchestrator.applyTraffic("r1", 80).outcome()
                    == GlobalAutonomyOrchestrator.Outcome.EXECUTED) {
                executed++;
            }
        }
        assertThat(executed).isEqualTo(budget);
    }

    @ParameterizedTest(name = "cap {0}")
    @ValueSource(ints = {1, 2, 5})
    void globalAutonomyRegionCaps(int cap) {
        RegionQuota quota = new RegionQuota();
        GlobalAutonomyOrchestrator orchestrator =
                new GlobalAutonomyOrchestrator(
                        new AutonomousCapacityController(
                                2, 5, 100, 100),
                        new AutonomousTrafficController(
                                quota, 0.5, 10, 200),
                        new GlobalAutonomyOrchestrator.Policy(
                                100, cap, true),
                        planId -> true);
        for (int i = 0; i < cap; i++) {
            quota.setQuota("r" + i, 50);
            assertThat(orchestrator.applyTraffic("r" + i, 80)
                    .outcome())
                    .isEqualTo(GlobalAutonomyOrchestrator
                            .Outcome.EXECUTED);
        }
        quota.setQuota("r" + cap, 50);
        assertThat(orchestrator.applyTraffic("r" + cap, 80)
                .outcome())
                .isEqualTo(GlobalAutonomyOrchestrator
                        .Outcome.REJECTED);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(longs = {10, 80, 500})
    void globalTrafficTargets(long target) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        AutonomousTrafficController controller =
                new AutonomousTrafficController(quota, 0.5, 10, 200);
        GlobalTrafficAutonomy autonomy =
                new GlobalTrafficAutonomy(controller,
                        List.of("r1"));
        autonomy.adjustAll(Map.of("r1", target));
        assertThat(quota.quota("r1")).isBetween(10L, 200L);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 2, 4, 8})
    void materializedViewShardCounts(int count) {
        MaterializedViewManager manager = manager();
        List<CloudShard> shards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            shards.add(new CloudShard("d" + i,
                    i % 2 == 0 ? "aws-us" : "gcp-us", "m"));
        }
        manager.create(new Definition("v", shards,
                Aggregate.SUM, 60_000));
        var snapshot = manager.refresh("v", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1));
        assertThat(snapshot.value()).isEqualTo(count);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void materializedViewAggregates(String aggregate) {
        MaterializedViewManager manager = manager();
        manager.create(new Definition("v", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.valueOf(aggregate), 60_000));
        var snapshot = manager.refresh("v", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 4, 1));
        assertThat(snapshot.count()).isPositive();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50})
    void materializedViewRefreshRounds(int rounds) {
        MaterializedViewManager manager = manager();
        manager.create(new Definition("v", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM, 0));
        for (int i = 0; i < rounds; i++) {
            manager.refresh("v", "aws-us",
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 2, 1));
        }
        assertThat(manager.query("v").value()).isEqualTo(2);
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 999, 1000, 2000})
    void complianceVersionTimes(long time) {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new RegulationVersion.Version("GDPR", "v1",
                1000, Set.of(new Control("g1", "residency", true))));
        ContinuousAuditPipeline pipeline = new ContinuousAuditPipeline(
                store, new AuditExporter());
        if (time < 1000) {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> pipeline.evaluate("GDPR", time,
                            controls -> new io.tieringkv.compliance
                                    .ComplianceReport()));
        } else {
            var run = pipeline.evaluate("GDPR", time,
                    controls -> new io.tieringkv.compliance
                            .ComplianceReport());
            assertThat(run.versionId()).isEqualTo("v1");
        }
    }

    @ParameterizedTest(name = "versions {0}")
    @ValueSource(ints = {1, 3, 10})
    void regulationVersionCounts(int count) {
        RegulationVersionStore store = new RegulationVersionStore();
        for (int i = 0; i < count; i++) {
            store.register(new RegulationVersion.Version("GDPR",
                    "v" + i, i * 100L, Set.of()));
        }
        assertThat(store.versionCount("GDPR")).isEqualTo(count);
        assertThat(store.history("GDPR")).hasSize(count);
    }

    @ParameterizedTest(name = "runs {0}")
    @ValueSource(ints = {1, 5, 20})
    void compliancePipelineRuns(int runs) {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new RegulationVersion.Version("GDPR", "v1",
                0, Set.of()));
        ContinuousAuditPipeline pipeline = new ContinuousAuditPipeline(
                store, new AuditExporter());
        for (int i = 0; i < runs; i++) {
            pipeline.evaluate("GDPR", 1,
                    controls -> new io.tieringkv.compliance
                            .ComplianceReport());
        }
        assertThat(pipeline.runCount()).isEqualTo(runs);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(longs = {0, 50, 200, 1000})
    void costOptimizerOps(long ops) {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        List<WorkloadCostOptimizer.Suggestion> suggestions =
                optimizer.analyze(new WorkloadProfile("t1", "d",
                        "aws-us", ops, ops / 2, 20, 1), 100);
        assertThat(suggestions).isNotNull();
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(longs = {0, 20, 50, 200})
    void costOptimizerStorage(long storageGB) {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        List<WorkloadCostOptimizer.Suggestion> suggestions =
                optimizer.analyze(new WorkloadProfile("t1", "d",
                        "aws-us", 100, 900, storageGB, 1), 100);
        boolean coldTier = suggestions.stream().anyMatch(
                s -> s.type() == WorkloadCostOptimizer
                        .SuggestionType.COLD_TIER);
        assertThat(coldTier).isEqualTo(storageGB >= 50);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(longs = {1, 63, 64, 128})
    void costOptimizerValueSizes(long sizeKB) {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        List<WorkloadCostOptimizer.Suggestion> suggestions =
                optimizer.analyze(new WorkloadProfile("t1", "d",
                        "aws-us", 1000, 500, 10, sizeKB), 100);
        boolean compression = suggestions.stream().anyMatch(
                s -> s.type() == WorkloadCostOptimizer
                        .SuggestionType.COMPRESSION);
        assertThat(compression).isEqualTo(sizeKB >= 64);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {2, 4, 10, 20})
    void networkIsolationTenantCounts(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        assertThat(policy.domainCount()).isEqualTo(count);
        assertThat(policy.canCommunicate("t0", "t1")).isFalse();
    }

    @ParameterizedTest(name = "pairs {0}")
    @ValueSource(ints = {1, 3, 8})
    void networkIsolationWhitelistPairs(int pairs) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i <= pairs; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc", "subnet", true));
        }
        for (int i = 0; i < pairs; i++) {
            policy.allow("t" + i, "t" + (i + 1));
        }
        for (int i = 0; i < pairs; i++) {
            assertThat(policy.canCommunicate("t" + i,
                    "t" + (i + 1))).isTrue();
        }
    }

    @ParameterizedTest(name = "window {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void sloWindowSizes(int window) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9,
                window));
        for (int i = 0; i < window * 2; i++) {
            manager.record("s", true);
        }
        assertThat(manager.compliance("s")).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(doubles = {0.5, 0.8, 0.95})
    void sloTargets(double target) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", target,
                10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", true);
        }
        assertThat(manager.status("s"))
                .isEqualTo(SloManager.Status.COMPLIANT);
    }

    @ParameterizedTest(name = "success {0}")
    @ValueSource(ints = {0, 5, 8, 10})
    void sloSuccessPatterns(int success) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", i < success);
        }
        assertThat(manager.compliance("s"))
                .isEqualTo(success / 10.0);
    }

    @Test
    void globalAutonomyMixedSequence() {
        GlobalAutonomyOrchestrator orchestrator = autonomy(10, 5);
        assertThat(orchestrator.applyCapacity(
                new io.tieringkv.capacity.ai.AutoCapacityAdvisor
                        .Advice("qps", 100, 200, 4, 2,
                        io.tieringkv.capacity.ai
                                .AutoCapacityAdvisor.RiskLevel.LOW,
                        0.9)).outcome())
                .isEqualTo(GlobalAutonomyOrchestrator
                        .Outcome.EXECUTED);
        assertThat(orchestrator.applyTraffic("r1", 80).outcome())
                .isEqualTo(GlobalAutonomyOrchestrator
                        .Outcome.EXECUTED);
        assertThat(orchestrator.applyReshard("plan").outcome())
                .isEqualTo(GlobalAutonomyOrchestrator
                        .Outcome.EXECUTED);
        assertThat(orchestrator.actionsToday()).isEqualTo(3);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20})
    void globalAutonomyTrafficRounds(int rounds) {
        GlobalAutonomyOrchestrator orchestrator = autonomy(100, 5);
        for (int i = 0; i < rounds; i++) {
            assertThat(orchestrator.applyTraffic("r1", 80)
                    .outcome())
                    .isEqualTo(GlobalAutonomyOrchestrator
                            .Outcome.EXECUTED);
        }
        assertThat(orchestrator.actionsToday()).isEqualTo(rounds);
    }

    @ParameterizedTest(name = "period {0}")
    @ValueSource(longs = {0, 1, 60_000, Long.MAX_VALUE})
    void materializedViewPeriods(long period) {
        MaterializedViewManager manager = manager();
        manager.create(new Definition("v", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM, period));
        manager.refresh("v", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 3, 1));
        boolean refreshed = manager.refreshIfDue("v", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 9, 1));
        assertThat(refreshed).isEqualTo(period == 0);
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(longs = {10, 100, 400, 900})
    void costOptimizerWriteRatios(long writes) {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        List<WorkloadCostOptimizer.Suggestion> suggestions =
                optimizer.analyze(new WorkloadProfile("t1", "d",
                        "aws-us", 100, writes, 80, 1), 100);
        boolean coldTier = suggestions.stream().anyMatch(
                s -> s.type() == WorkloadCostOptimizer
                        .SuggestionType.COLD_TIER);
        assertThat(coldTier)
                .isEqualTo((double) writes / (100 + writes) >= 0.8);
    }

    @ParameterizedTest(name = "private {0}")
    @ValueSource(booleans = {true, false})
    void networkIsolationPrivateFlags(boolean privacy) {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc", "subnet", privacy));
        assertThat(policy.isPrivate("t1")).isEqualTo(privacy);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 100})
    void sloRecordRounds(int rounds) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < rounds; i++) {
            manager.record("s", true);
        }
        assertThat(manager.compliance("s")).isEqualTo(1.0);
    }

    private static GlobalAutonomyOrchestrator autonomy(
            int budget, int regionCap) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        return new GlobalAutonomyOrchestrator(
                new AutonomousCapacityController(2, 5, 100, 100),
                new AutonomousTrafficController(quota, 0.5, 10, 200),
                new GlobalAutonomyOrchestrator.Policy(
                        budget, regionCap, true),
                planId -> true);
    }

    private static MaterializedViewManager manager() {
        return new MaterializedViewManager(
                new CloudFederatedExecutor(
                        new io.tieringkv.compliance
                                .ComplianceValidator(),
                        new io.tieringkv.compliance
                                .DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us"))));
    }
}
