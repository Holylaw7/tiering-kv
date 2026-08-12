package io.tieringkv.datamesh;

import io.tieringkv.datamesh.DomainCatalog.Domain;
import io.tieringkv.datamesh.FederatedPlanner.Aggregate;
import io.tieringkv.datamesh.FederatedPlanner.Plan;
import io.tieringkv.datamesh.FederatedPlanner.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 数据网格联邦规划（ADR-0148）：跨域查询分片 + 域隔离。 */
class FederatedPlannerTest {

    private FederatedPlanner planner;

    @BeforeEach
    void setUp() {
        DomainCatalog catalog = new DomainCatalog();
        catalog.register(new Domain("orders", "team-a",
                Set.of("ADMIN", "ANALYST")));
        catalog.register(new Domain("payments", "team-b",
                Set.of("ADMIN", "ANALYST")));
        planner = new FederatedPlanner(catalog);
    }

    @Test
    void singleDomainPlan() {
        Plan plan = planner.plan(
                new Query("revenue", "SUM",
                        List.of("orders")), "ANALYST");
        assertThat(plan.aggregate()).isEqualTo(Aggregate.SUM);
        assertThat(plan.shards()).hasSize(1);
        assertThat(plan.shards().get(0).domainId())
                .isEqualTo("orders");
    }

    @Test
    void crossDomainPlan() {
        Plan plan = planner.plan(
                new Query("volume", "COUNT",
                        List.of("orders", "payments")), "ADMIN");
        assertThat(plan.shards()).hasSize(2);
        assertThat(plan.shards()).extracting(
                FederatedPlanner.Shard::domainId)
                .containsExactly("orders", "payments");
    }

    @Test
    void unauthorizedRoleRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query("revenue", "SUM",
                        List.of("orders")), "GUEST"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void unknownDomainRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query("revenue", "SUM",
                        List.of("missing")), "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownAggregateRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query("revenue", "MEDIAN",
                        List.of("orders")), "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankMetricRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query("", "SUM", List.of("orders")), "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullMetricRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query(null, "SUM", List.of("orders")), "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyDomainsRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query("revenue", "SUM", List.of()), "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankAggregateRejected() {
        assertThatThrownBy(() -> planner.plan(
                new Query("revenue", " ", List.of("orders")),
                "ADMIN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aggregateCaseInsensitive() {
        Plan plan = planner.plan(
                new Query("revenue", "sum",
                        List.of("orders")), "ADMIN");
        assertThat(plan.aggregate()).isEqualTo(Aggregate.SUM);
    }

    @Test
    void planShardsCarryMetric() {
        Plan plan = planner.plan(
                new Query("latency_p99", "MAX",
                        List.of("orders", "payments")), "ANALYST");
        assertThat(plan.metric()).isEqualTo("latency_p99");
        assertThat(plan.shards().get(1).metric())
                .isEqualTo("latency_p99");
    }

    @Test
    void planIsImmutableCopy() {
        List<String> domains = new java.util.ArrayList<>(
                List.of("orders"));
        Plan plan = planner.plan(
                new Query("revenue", "SUM", domains), "ADMIN");
        domains.add("payments");
        assertThat(plan.shards()).hasSize(1);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedAggregates(String aggregate) {
        Plan plan = planner.plan(
                new Query("revenue", aggregate,
                        List.of("orders")), "ADMIN");
        assertThat(plan.aggregate().name()).isEqualTo(aggregate);
    }

    @ParameterizedTest(name = "domains {0}")
    @ValueSource(strings = {"1", "2"})
    void parameterizedDomainCounts(int count) {
        List<String> domains = count == 1
                ? List.of("orders")
                : List.of("orders", "payments");
        Plan plan = planner.plan(
                new Query("revenue", "SUM", domains), "ADMIN");
        assertThat(plan.shards()).hasSize(count);
    }

    @ParameterizedTest(name = "role {0}")
    @ValueSource(strings = {"ADMIN", "ANALYST"})
    void parameterizedAuthorizedRoles(String role) {
        Plan plan = planner.plan(
                new Query("revenue", "SUM",
                        List.of("orders", "payments")), role);
        assertThat(plan.shards()).hasSize(2);
    }
}
