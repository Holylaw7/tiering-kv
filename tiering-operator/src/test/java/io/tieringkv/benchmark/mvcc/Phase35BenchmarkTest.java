package io.tieringkv.benchmark.mvcc;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor;
import io.tieringkv.capacity.ai.AutonomousCapacityController;
import io.tieringkv.capacity.ai.GlobalAutonomyOrchestrator;
import io.tieringkv.capacity.ai.TrendPredictor;
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
import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.observability.cost.CostAttribution;
import io.tieringkv.observability.cost.CostAttribution.CostEntry;
import io.tieringkv.observability.cost.WorkloadCostOptimizer;
import io.tieringkv.observability.cost.WorkloadCostOptimizer.WorkloadProfile;
import io.tieringkv.operations.slo.SloAlert;
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

/** Phase 35 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase35BenchmarkTest {

    @ParameterizedTest(name = "actions {0}")
    @ValueSource(ints = {1000, 10000})
    void globalAutonomyThroughput(int actions) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        AutonomousTrafficController traffic =
                new AutonomousTrafficController(quota, 0.5, 10, 200);
        AutonomousCapacityController capacity =
                new AutonomousCapacityController(2, 5, 1000, 100);
        GlobalAutonomyOrchestrator orchestrator =
                new GlobalAutonomyOrchestrator(capacity, traffic,
                        new GlobalAutonomyOrchestrator.Policy(
                                1000, 10, true),
                        planId -> true);
        AutoCapacityAdvisor advisor = new AutoCapacityAdvisor(
                new CapacityPlanner(), new TrendPredictor());
        List<TrendPredictor.Point> history = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            history.add(new TrendPredictor.Point(i, 100 + 5.0 * i));
        }
        long start = System.nanoTime();
        for (int i = 0; i < actions; i++) {
            orchestrator.applyTraffic("r1", 80);
            orchestrator.applyCapacity(advisor.adviseLinear("qps",
                    history, 30, 8, 1000,
                    capacity.currentNodes(), 500, 100));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE35-BENCH GLOBAL-AUTO %d -> %d ops/s%n",
                actions, actions * 2_000L / elapsedMs);
    }

    @ParameterizedTest(name = "refreshes {0}")
    @ValueSource(ints = {1000, 10000})
    void materializedViewRefreshThroughput(int refreshes) {
        MaterializedViewManager manager = new MaterializedViewManager(
                new CloudFederatedExecutor(
                        new io.tieringkv.compliance
                                .ComplianceValidator(),
                        new io.tieringkv.compliance
                                .DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("orders", "aws-us", "m"),
                new CloudShard("payments", "gcp-us", "m")),
                Aggregate.SUM, 0));
        long start = System.nanoTime();
        for (int i = 0; i < refreshes; i++) {
            manager.refresh("v1", "aws-us",
                    shard -> new CloudResult(shard.domainId(),
                            shard.cloud(), 1, 1));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE35-BENCH MATERIALIZED %d -> %d ops/s%n",
                refreshes, refreshes * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "runs {0}")
    @ValueSource(ints = {100, 1000})
    void compliancePipelineThroughput(int runs) {
        RegulationVersionStore store = new RegulationVersionStore();
        store.register(new RegulationVersion.Version("GDPR", "v1",
                0, Set.of(new Control("g1", "residency", true))));
        ContinuousAuditPipeline pipeline = new ContinuousAuditPipeline(
                store, new AuditExporter());
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            pipeline.evaluate("GDPR", 1,
                    controls -> new io.tieringkv.compliance
                            .ComplianceReport());
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE35-BENCH COMPLIANCE %d -> %d runs/s%n",
                runs, runs * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "profiles {0}")
    @ValueSource(ints = {100, 1000})
    void costOptimizerThroughput(int profiles) {
        WorkloadCostOptimizer optimizer =
                new WorkloadCostOptimizer();
        CostAttribution costs = new CostAttribution();
        Map<String, WorkloadProfile> profileMap =
                new java.util.HashMap<>();
        for (int i = 0; i < profiles; i++) {
            profileMap.put("t" + i, new WorkloadProfile(
                    "t" + i, "d", "aws-us", 10, 90, 100, 128));
            costs.add(new CostEntry("t" + i, "d", "aws-us",
                    "storage", 100));
        }
        long start = System.nanoTime();
        optimizer.analyzeAll(profileMap, costs);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE35-BENCH COST-OPT %d -> %d profiles/s%n",
                profiles, profiles * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "checks {0}")
    @ValueSource(ints = {1000, 10000})
    void networkIsolationThroughput(int checks) {
        IsolationPolicy policy = new IsolationPolicy();
        for (int i = 0; i < 10; i++) {
            policy.register(new NetworkIsolationDomain(
                    "t" + i, "vpc-" + i, "subnet-" + i, true));
        }
        long start = System.nanoTime();
        for (int i = 0; i < checks; i++) {
            policy.canCommunicate("t" + (i % 10),
                    "t" + ((i + 1) % 10));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE35-BENCH NET-ISO %d -> %d checks/s%n",
                checks, checks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {1000, 10000})
    void sloManagerThroughput(int records) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 100));
        long start = System.nanoTime();
        for (int i = 0; i < records; i++) {
            manager.record("s", i % 10 != 0);
        }
        assertThat(new SloAlert().evaluate(manager,
                List.of("s"))).isNotNull();
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE35-BENCH SLO %d -> %d records/s%n",
                records, records * 1_000L / elapsedMs);
    }
}
