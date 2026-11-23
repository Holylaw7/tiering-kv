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
import io.tieringkv.gateway.RegionQuota;
import io.tieringkv.observability.cost.CostAttribution;
import io.tieringkv.observability.cost.CostAttribution.CostEntry;
import io.tieringkv.observability.cost.WorkloadCostOptimizer;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.Suggestion;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.WorkloadProfile;
import io.tieringkv.operations.slo.SloAlert;
import io.tieringkv.operations.slo.SloManager;
import io.tieringkv.operations.slo.SloManager.SloDefinition;
import io.tieringkv.security.network.IsolationPolicy;
import io.tieringkv.security.network.NetworkIsolationDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 35 生产门禁（JVM 级）：自治围栏 + 物化 + 合规 + 隔离 + SLO。 */
class Phase35ProductionGateTest {

    @Test
    void globalAutonomyRollbackGate() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        AutonomousTrafficController traffic =
                new AutonomousTrafficController(quota, 0.5, 10, 200);
        AutonomousCapacityController capacity =
                new AutonomousCapacityController(2, 5, 100, 100);
        GlobalAutonomyOrchestrator orchestrator =
                new GlobalAutonomyOrchestrator(capacity, traffic,
                        new GlobalAutonomyOrchestrator.Policy(
                                10, 5, true),
                        planId -> true);
        orchestrator.applyTraffic("r1", 100);
        orchestrator.rollback();
        assertThat(quota.quota("r1")).isEqualTo(50);
        assertThat(capacity.currentNodes()).isEqualTo(2);
    }

    @Test
    void globalAutonomyDailyBudgetGate() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        GlobalAutonomyOrchestrator orchestrator =
                new GlobalAutonomyOrchestrator(
                        new AutonomousCapacityController(
                                2, 5, 100, 100),
                        new AutonomousTrafficController(
                                quota, 0.5, 10, 200),
                        new GlobalAutonomyOrchestrator.Policy(
                                1, 5, true),
                        planId -> true);
        orchestrator.applyTraffic("r1", 80);
        assertThat(orchestrator.applyTraffic("r1", 90).outcome())
                .isEqualTo(GlobalAutonomyOrchestrator.Outcome.REJECTED);
    }

    @Test
    void materializedViewStaleMarkingGate() {
        MaterializedViewManager manager = new MaterializedViewManager(
                new CloudFederatedExecutor(
                        new io.tieringkv.compliance
                                .ComplianceValidator(),
                        new io.tieringkv.compliance
                                .DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("orders", "aws-us", "m")),
                Aggregate.SUM, 60_000));
        assertThat(manager.isStale("v1")).isTrue();
        manager.refresh("v1", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 5, 1));
        assertThat(manager.isStale("v1")).isFalse();
        manager.invalidate("v1");
        assertThat(manager.isStale("v1")).isTrue();
    }

    @Test
    void materializedViewCrossResidencyGate() {
        MaterializedViewManager manager = new MaterializedViewManager(
                new CloudFederatedExecutor(
                        new io.tieringkv.compliance
                                .ComplianceValidator(),
                        new io.tieringkv.compliance
                                .DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-eu", "eu"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("a", "aws-us", "m"),
                new CloudShard("b", "gcp-eu", "m")),
                Aggregate.SUM, 60_000));
        assertThatThrownBy(() -> manager.refresh("v1", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void complianceVersionSwitchGate() {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new RegulationVersion.Version("GDPR", "v1",
                0, Set.of(new Control("g1", "residency", true),
                        new Control("g2", "erasure", false))));
        store.register(new RegulationVersion.Version("GDPR", "v2",
                1000, Set.of(new Control("g1", "residency", true),
                        new Control("g2", "erasure", true))));
        ContinuousAuditPipeline pipeline = new ContinuousAuditPipeline(
                store, new AuditExporter());
        var runV1 = pipeline.evaluate("GDPR", 500,
                controls -> reportWithUnimplemented(controls));
        var runV2 = pipeline.evaluate("GDPR", 1500,
                controls -> reportWithUnimplemented(controls));
        assertThat(runV1.versionId()).isEqualTo("v1");
        assertThat(runV2.versionId()).isEqualTo("v2");
        assertThat(runV1.violations()).isEqualTo(1);
        assertThat(runV2.violations()).isZero();
    }

    @Test
    void costOptimizerSuggestionGate() {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        CostAttribution costs = new CostAttribution();
        costs.add(new CostEntry("t1", "orders", "aws-us",
                "storage", 100));
        List<Suggestion> suggestions = optimizer.analyzeAll(Map.of(
                "t1", new WorkloadProfile("t1", "orders", "aws-us",
                        10, 90, 100, 128)),
                costs);
        assertThat(suggestions).extracting(Suggestion::type)
                .containsExactlyInAnyOrder(
                        WorkloadCostOptimizer.SuggestionType.COLD_TIER,
                        WorkloadCostOptimizer.SuggestionType.COMPRESSION);
        assertThat(suggestions).allMatch(
                s -> s.estimatedSavings() > 0);
    }

    @Test
    void networkIsolationDefaultDenyGate() {
        IsolationPolicy policy = new IsolationPolicy();
        policy.register(new NetworkIsolationDomain(
                "t1", "vpc-1", "subnet-1", true));
        policy.register(new NetworkIsolationDomain(
                "t2", "vpc-2", "subnet-2", true));
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
        policy.allow("t1", "t2");
        assertThat(policy.canCommunicate("t2", "t1")).isTrue();
        policy.deny("t1", "t2");
        assertThat(policy.canCommunicate("t1", "t2")).isFalse();
    }

    @Test
    void sloBreachAlertGate() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("latency", "latency",
                0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("latency", i < 5);
        }
        List<SloAlert.Alert> alerts = new SloAlert().evaluate(
                manager, List.of("latency"));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).status()).isEqualTo("BREACHED");
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {5, 20})
    void parameterizedIsolationCounts(int count) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < count; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                assertThat(policy.canCommunicate("t" + i, "t" + j))
                        .isEqualTo(i == j);
            }
        }
    }

    @ParameterizedTest(name = "windows {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedSloWindows(int window) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9,
                window));
        for (int i = 0; i < window; i++) {
            manager.record("s", true);
        }
        assertThat(manager.compliance("s")).isEqualTo(1.0);
    }

    @Test
    void autonomyRegionCapGate() {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        quota.setQuota("r2", 50);
        GlobalAutonomyOrchestrator orchestrator =
                new GlobalAutonomyOrchestrator(
                        new AutonomousCapacityController(
                                2, 5, 100, 100),
                        new AutonomousTrafficController(
                                quota, 0.5, 10, 200),
                        new GlobalAutonomyOrchestrator.Policy(
                                10, 1, true),
                        planId -> true);
        orchestrator.applyTraffic("r1", 80);
        assertThat(orchestrator.applyTraffic("r2", 80).outcome())
                .isEqualTo(GlobalAutonomyOrchestrator.Outcome.REJECTED);
    }

    @Test
    void compliancePipelineAuditTrailGate() {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new RegulationVersion.Version("GDPR", "v1",
                0, Set.of(new Control("g1", "residency", true))));
        ContinuousAuditPipeline pipeline = new ContinuousAuditPipeline(
                store, new AuditExporter());
        pipeline.evaluate("GDPR", 1,
                controls -> new io.tieringkv.compliance
                        .ComplianceReport());
        pipeline.evaluate("GDPR", 2,
                controls -> new io.tieringkv.compliance
                        .ComplianceReport());
        assertThat(pipeline.runCount()).isEqualTo(2);
        assertThat(pipeline.runsFor("GDPR")).hasSize(2);
    }

    @Test
    void costSuggestionRiskAndSavingsGate() {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        List<Suggestion> suggestions = optimizer.analyze(
                new WorkloadProfile("t1", "orders", "aws-us",
                        10, 90, 100, 128),
                200);
        assertThat(suggestions).extracting(
                Suggestion::estimatedSavings)
                .contains(100.0, 30.0);
        assertThat(suggestions).extracting(Suggestion::risk)
                .doesNotContainNull();
    }

    private static io.tieringkv.compliance.ComplianceReport
            reportWithUnimplemented(Set<Control> controls) {
        io.tieringkv.compliance.ComplianceReport report =
                new io.tieringkv.compliance.ComplianceReport();
        controls.stream().filter(c -> !c.implemented()).forEach(c ->
                report.add(new io.tieringkv.compliance
                        .ComplianceReport.Violation("GDPR",
                        c.controlId(),
                        io.tieringkv.compliance.ComplianceReport
                                .Severity.HIGH,
                        "not implemented")));
        return report;
    }
}
