package io.tieringkv.sql.distributed;

import io.tieringkv.sql.AggregateType;
import io.tieringkv.sql.SqlEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分布式 SQL（ADR-0120）：分片计划、两阶段聚合、合并 JOIN。 */
class DistributedSqlTest {

    @Test
    void shardPlannerRoundRobin() {
        ShardPlanner planner = new ShardPlanner();
        List<ShardPlan> plans = planner.plan(
                List.of("r1", "r2", "r3"), 6, "k");
        assertThat(plans).hasSize(6);
        assertThat(plans.get(0).region()).isEqualTo("r1");
        assertThat(plans.get(1).region()).isEqualTo("r2");
        assertThat(plans.get(2).region()).isEqualTo("r3");
        assertThat(plans.get(3).region()).isEqualTo("r1");
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 4, 16})
    void parameterizedShardCounts(int shardCount) {
        ShardPlanner planner = new ShardPlanner();
        List<ShardPlan> plans = planner.plan(
                List.of("r1", "r2"), shardCount, "user:");
        assertThat(plans).hasSize(shardCount);
        assertThat(plans.get(0).startKey()).isEqualTo(
                "user:000000".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedRegionDistribution(int regions) {
        List<String> regionNames = new ArrayList<>();
        for (int i = 0; i < regions; i++) {
            regionNames.add("r" + i);
        }
        ShardPlanner planner = new ShardPlanner();
        List<ShardPlan> plans = planner.plan(regionNames, 10, "k");
        assertThat(plans).extracting(ShardPlan::region)
                .containsOnlyElementsOf(regionNames);
    }

    @Test
    void emptyRegionsRejected() {
        assertThatThrownBy(() -> new ShardPlanner().plan(
                List.of(), 4, "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroShardsRejected() {
        assertThatThrownBy(() -> new ShardPlanner().plan(
                List.of("r1"), 0, "k"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shardForKeyWithinPrefix() {
        ShardPlanner planner = new ShardPlanner();
        int index = planner.shardFor(
                "user:abc".getBytes(StandardCharsets.UTF_8), 8, "user:");
        assertThat(index).isBetween(0, 7);
    }

    @Test
    void shardForKeyOutsidePrefixRejected() {
        assertThatThrownBy(() -> new ShardPlanner().shardFor(
                "other".getBytes(StandardCharsets.UTF_8), 8, "user:"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void partialAggregateOfValue() {
        PartialAggregate partial = PartialAggregate.of(42);
        assertThat(partial.count()).isEqualTo(1);
        assertThat(partial.sum()).isEqualTo(42);
        assertThat(partial.min()).isEqualTo(42);
        assertThat(partial.max()).isEqualTo(42);
    }

    @Test
    void emptyPartialAggregateNormalized() {
        PartialAggregate partial = new PartialAggregate(0, 0, 0, 0);
        assertThat(partial.min()).isEqualTo(Long.MAX_VALUE);
        assertThat(partial.max()).isEqualTo(Long.MIN_VALUE);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void mergeAggregateCount(int count) {
        List<PartialAggregate> partials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            partials.add(PartialAggregate.of(1));
        }
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.COUNT)).isEqualTo(count);
    }

    @Test
    void mergeAggregateSum() {
        List<PartialAggregate> partials = List.of(
                PartialAggregate.of(10), PartialAggregate.of(20));
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.SUM)).isEqualTo(30);
    }

    @Test
    void mergeAggregateAvg() {
        List<PartialAggregate> partials = List.of(
                new PartialAggregate(2, 10, 0, 0),
                new PartialAggregate(2, 30, 0, 0));
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.AVG)).isEqualTo(10);
    }

    @Test
    void mergeAggregateEmptyAvgZero() {
        assertThat(new MergeAggregate().merge(List.of(),
                AggregateType.AVG)).isZero();
    }

    @Test
    void mergeJoinDeduplicates() {
        SqlEngine.Row row = new SqlEngine.Row(bytes("k1"), bytes("v"));
        MergeJoin join = new MergeJoin();
        List<SqlEngine.Row> merged = join.merge(List.of(
                List.of(row), List.of(row)));
        assertThat(merged).hasSize(1);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3})
    void mergeJoinAcrossRegions(int regions) {
        List<List<SqlEngine.Row>> results = new ArrayList<>();
        for (int r = 0; r < regions; r++) {
            List<SqlEngine.Row> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                rows.add(new SqlEngine.Row(
                        bytes("k" + r + "-" + i), bytes("v")));
            }
            results.add(rows);
        }
        assertThat(new MergeJoin().merge(results))
                .hasSize(regions * 10);
    }

    @Test
    void distributedExecutorMergesPlans() {
        DistributedExecutor executor = new DistributedExecutor();
        List<ShardPlan> plans = new ShardPlanner().plan(
                List.of("r1", "r2"), 4, "k");
        List<SqlEngine.Row> rows = executor.execute(plans,
                plan -> List.of(new SqlEngine.Row(
                        plan.startKey(), bytes("v"))));
        assertThat(rows).hasSize(4);
    }

    @Test
    void distributedExecutorEmptyPlans() {
        assertThat(new DistributedExecutor().execute(List.of(),
                plan -> List.of())).isEmpty();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
