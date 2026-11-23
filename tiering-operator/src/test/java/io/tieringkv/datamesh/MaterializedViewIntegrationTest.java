package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.DomainCatalog.Domain;
import io.tieringkv.datamesh.FederatedPlanner.Plan;
import io.tieringkv.datamesh.FederatedPlanner.Query;
import io.tieringkv.datamesh.MaterializedViewManager.Definition;
import io.tieringkv.datamesh.MaterializedViewManager.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 物化视图集成（ADR-0158）：规划 → 跨云聚合 → 物化查询。 */
class MaterializedViewIntegrationTest {

    private FederatedPlanner planner;
    private MaterializedViewManager manager;

    @BeforeEach
    void setUp() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN")));
        catalog.register(new Domain("payments", "team-b",
                Set.of("ADMIN")));
        planner = new FederatedPlanner(catalog);
        manager = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us"))));
    }

    @Test
    void plannedViewRefreshAndQuery() {
        Plan plan = planner.plan(new Query("revenue", "SUM",
                List.of("orders", "payments")), "ADMIN");
        Definition definition = new Definition("revenue-view",
                plan.shards().stream()
                        .map(shard -> new CloudShard(
                                shard.domainId(),
                                shard.domainId().equals("orders")
                                        ? "aws-us" : "gcp-us",
                                shard.metric()))
                        .toList(),
                Aggregate.SUM, 60_000);
        manager.create(definition);
        assertThat(manager.isStale("revenue-view")).isTrue();
        Snapshot snapshot = manager.refresh("revenue-view",
                "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        shard.domainId().equals("orders") ? 10 : 20,
                        1));
        assertThat(snapshot.value()).isEqualTo(30);
        assertThat(snapshot.stale()).isFalse();
    }

    @Test
    void invalidateAfterRefreshMarksStale() {
        plannedViewRefreshAndQuery();
        manager.invalidate("revenue-view");
        assertThat(manager.isStale("revenue-view")).isTrue();
        assertThat(manager.query("revenue-view").value())
                .isEqualTo(30);
    }

    @Test
    void refreshIfDueNeverRefreshesWithinLongPeriod() {
        plannedViewRefreshAndQuery();
        assertThat(manager.refreshIfDue("revenue-view", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 99, 1))).isFalse();
        assertThat(manager.query("revenue-view").value())
                .isEqualTo(30);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedPlannedAggregates(String aggregate) {
        Plan plan = planner.plan(new Query("revenue", aggregate,
                List.of("orders", "payments")), "ADMIN");
        Definition definition = new Definition("view-" + aggregate,
                plan.shards().stream()
                        .map(shard -> new CloudShard(
                                shard.domainId(), "aws-us",
                                shard.metric()))
                        .toList(),
                Aggregate.valueOf(aggregate), 60_000);
        manager.create(definition);
        Snapshot snapshot = manager.refresh("view-" + aggregate,
                "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 4, 1));
        assertThat(snapshot.count()).isPositive();
    }

    @ParameterizedTest(name = "domains {0}")
    @ValueSource(ints = {1, 2})
    void parameterizedPlannedDomains(int count) {
        List<String> domains = count == 1
                ? List.of("orders")
                : List.of("orders", "payments");
        Plan plan = planner.plan(new Query("revenue", "SUM",
                domains), "ADMIN");
        List<CloudShard> shards = new ArrayList<>();
        for (var shard : plan.shards()) {
            shards.add(new CloudShard(shard.domainId(), "aws-us",
                    shard.metric()));
        }
        manager.create(new Definition("v" + count, shards,
                Aggregate.SUM, 60_000));
        Snapshot snapshot = manager.refresh("v" + count, "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 1, 1));
        assertThat(snapshot.value()).isEqualTo(count);
    }

    @Test
    void concurrentRefreshAndQueryStable() throws Exception {
        plannedViewRefreshAndQuery();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                manager.refresh("revenue-view", "aws-us",
                        shard -> new CloudResult(shard.domainId(),
                                shard.cloud(), 10, 1));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                manager.query("revenue-view");
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(manager.query("revenue-view").value())
                .isEqualTo(20);
    }

    @Test
    void multipleViewsIndependent() {
        manager.create(new Definition("a", List.of(
                new CloudShard("orders", "aws-us", "m")),
                Aggregate.SUM, 60_000));
        manager.create(new Definition("b", List.of(
                new CloudShard("payments", "gcp-us", "m")),
                Aggregate.COUNT, 60_000));
        manager.refresh("a", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 5, 1));
        assertThat(manager.query("a").value()).isEqualTo(5);
        assertThat(manager.isStale("b")).isTrue();
    }

    @Test
    void dropPlannedView() {
        plannedViewRefreshAndQuery();
        manager.drop("revenue-view");
        assertThat(manager.size()).isZero();
    }
}
