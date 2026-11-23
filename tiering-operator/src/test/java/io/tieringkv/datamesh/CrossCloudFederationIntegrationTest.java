package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudAggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.DomainCatalog.Domain;
import io.tieringkv.datamesh.FederatedPlanner.Plan;
import io.tieringkv.datamesh.FederatedPlanner.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云联邦集成（ADR-0152）：域规划 → 主权校验 → 跨云执行。 */
class CrossCloudFederationIntegrationTest {

    private FederatedPlanner planner;
    private CloudFederatedExecutor executor;

    @BeforeEach
    void setUp() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN")));
        catalog.register(new Domain("payments", "team-b",
                Set.of("ADMIN")));
        planner = new FederatedPlanner(catalog);
        executor = new CloudFederatedExecutor(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us",
                        "gcp-us", "us",
                        "aws-eu", "eu")));
    }

    @Test
    void plannedCrossCloudQueryExecutes() {
        Plan plan = planner.plan(new Query("revenue", "SUM",
                List.of("orders", "payments")), "ADMIN");
        List<CloudShard> shards = new ArrayList<>();
        for (FederatedPlanner.Shard shard : plan.shards()) {
            shards.add(new CloudShard(shard.domainId(),
                    shard.domainId().equals("orders")
                            ? "aws-us" : "gcp-us",
                    shard.metric()));
        }
        CloudAggregate result = executor.execute("aws-us", shards,
                cloudShard -> new CloudResult(cloudShard.domainId(),
                        cloudShard.cloud(),
                        cloudShard.domainId().equals("orders")
                                ? 10 : 20, 1),
                CloudFederatedExecutor.Aggregate.SUM);
        assertThat(result.value()).isEqualTo(30);
    }

    @Test
    void crossResidencyPlannedQueryRejected() {
        Plan plan = planner.plan(new Query("revenue", "SUM",
                List.of("orders", "payments")), "ADMIN");
        List<CloudShard> shards = List.of(
                new CloudShard("orders", "aws-us", "revenue"),
                new CloudShard("payments", "aws-eu", "revenue"));
        assertThatThrownBy(() -> executor.execute("aws-us", shards,
                cloudShard -> new CloudResult(cloudShard.domainId(),
                        cloudShard.cloud(), 1, 1),
                CloudFederatedExecutor.Aggregate.SUM))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void unauthorizedDomainPlanRejectedBeforeExecution() {
        assertThatThrownBy(() -> planner.plan(new Query(
                "revenue", "SUM", List.of("orders")), "GUEST"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void singleDomainCrossCloudSum() {
        Plan plan = planner.plan(new Query("revenue", "SUM",
                List.of("orders")), "ADMIN");
        List<CloudShard> shards = List.of(
                new CloudShard("orders", "aws-us", "revenue"),
                new CloudShard("orders", "gcp-us", "revenue"));
        CloudAggregate result = executor.execute("aws-us", shards,
                cloudShard -> new CloudResult(cloudShard.domainId(),
                        cloudShard.cloud(), 5, 1),
                CloudFederatedExecutor.Aggregate.SUM);
        assertThat(result.value()).isEqualTo(10);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedCrossCloudAggregates(String aggregate) {
        List<CloudShard> shards = List.of(
                new CloudShard("orders", "aws-us", "m"),
                new CloudShard("payments", "gcp-us", "m"));
        CloudAggregate result = executor.execute("aws-us", shards,
                cloudShard -> new CloudResult(cloudShard.domainId(),
                        cloudShard.cloud(),
                        cloudShard.domainId().equals("orders")
                                ? 4 : 8, 1),
                CloudFederatedExecutor.Aggregate.valueOf(aggregate));
        assertThat(result.count()).isPositive();
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
        for (FederatedPlanner.Shard shard : plan.shards()) {
            shards.add(new CloudShard(shard.domainId(), "aws-us",
                    shard.metric()));
        }
        CloudAggregate result = executor.execute("aws-us", shards,
                cloudShard -> new CloudResult(cloudShard.domainId(),
                        cloudShard.cloud(), 1, 1),
                CloudFederatedExecutor.Aggregate.SUM);
        assertThat(result.value()).isEqualTo(count);
    }

    @Test
    void domainCatalogAuthorizedAcrossClouds() {
        assertThat(planner.plan(new Query("revenue", "SUM",
                List.of("orders", "payments")), "ADMIN").shards())
                .hasSize(2);
    }

    @Test
    void crossCloudJoinAfterAggregation() {
        FederatedExecutor joinExecutor = new FederatedExecutor();
        List<FederatedExecutor.JoinRow> joined = joinExecutor.join(
                List.of(new FederatedExecutor.Row(
                        "k", "orders", 10)),
                List.of(new FederatedExecutor.Row(
                        "k", "payments", 20)));
        assertThat(joined.get(0).right()).isEqualTo(20);
    }

    @Test
    void mixedResidencyShardsRejectedDeterministically() {
        List<CloudShard> shards = List.of(
                new CloudShard("orders", "aws-us", "m"),
                new CloudShard("payments", "aws-eu", "m"),
                new CloudShard("analytics", "gcp-us", "m"));
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> executor.execute("aws-us",
                    shards,
                    cloudShard -> new CloudResult(
                            cloudShard.domainId(),
                            cloudShard.cloud(), 1, 1),
                    CloudFederatedExecutor.Aggregate.SUM))
                    .isInstanceOf(SecurityException.class);
        }
    }
}
