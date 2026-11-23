package io.tieringkv.platform;

import io.tieringkv.sql.AggregateType;
import io.tieringkv.sql.SqlEngine;
import io.tieringkv.sql.distributed.DistributedExecutor;
import io.tieringkv.sql.distributed.MergeAggregate;
import io.tieringkv.sql.distributed.PartialAggregate;
import io.tieringkv.sql.distributed.ShardPlan;
import io.tieringkv.sql.distributed.ShardPlanner;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.cluster.VectorShardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 29 分布式边缘：SQL 分片/聚合/执行与向量分片参数矩阵。 */
class Phase29DistributedEdgeTest {

    @ParameterizedTest(name = "prefix {0}")
    @ValueSource(strings = {"k", "user:", "order-2026-"})
    void shardPlanPrefixes(String prefix) {
        ShardPlanner planner = new ShardPlanner();
        List<ShardPlan> plans = planner.plan(
                List.of("r1", "r2"), 4, prefix);
        assertThat(plans).hasSize(4);
        assertThat(plans.get(0).startKey()).isEqualTo(
                (prefix + "000000").getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 3, 32})
    void shardForKeyAllShards(int shards) {
        ShardPlanner planner = new ShardPlanner();
        int index = planner.shardFor(
                "k".getBytes(StandardCharsets.UTF_8), shards, "k");
        assertThat(index).isBetween(0, shards - 1);
    }

    @Test
    void partialAggregateValues() {
        assertThat(PartialAggregate.of(7).sum()).isEqualTo(7);
        assertThat(PartialAggregate.of(7).count()).isEqualTo(1);
    }

    @ParameterizedTest(name = "partials {0}")
    @ValueSource(ints = {1, 50, 200})
    void mergeAggregateVolume(int count) {
        List<PartialAggregate> partials = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            partials.add(PartialAggregate.of(1));
        }
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.COUNT)).isEqualTo(count);
    }

    @Test
    void mergeAggregateMixed() {
        List<PartialAggregate> partials = List.of(
                new PartialAggregate(2, 5, 0, 0),
                new PartialAggregate(3, 15, 0, 0));
        assertThat(new MergeAggregate().merge(partials,
                AggregateType.AVG)).isEqualTo(4);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {2, 4})
    void distributedExecutorRegionCount(int regions) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < regions; i++) {
            names.add("r" + i);
        }
        DistributedExecutor executor = new DistributedExecutor();
        List<SqlEngine.Row> rows = executor.execute(
                new ShardPlanner().plan(names, 8, "k"),
                plan -> List.of(new SqlEngine.Row(
                        plan.startKey(), plan.endKey())));
        assertThat(rows).hasSize(8);
    }

    @Test
    void distributedExecutorDeduplicates() {
        DistributedExecutor executor = new DistributedExecutor();
        List<SqlEngine.Row> rows = executor.execute(
                List.of(new ShardPlan("r1", bytes("a"), bytes("b"))),
                plan -> List.of(
                        new SqlEngine.Row(bytes("k"), bytes("v")),
                        new SqlEngine.Row(bytes("k"), bytes("v"))));
        assertThat(rows).hasSize(1);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 2, 8})
    void vectorShardManagerShardCounts(int shards) {
        VectorShardManager manager = new VectorShardManager(shards);
        for (int i = 0; i < 100; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{i % 4, 4 - i % 4}));
        }
        assertThat(manager.totalSize()).isEqualTo(100);
        assertThat(manager.search(new float[]{1, 1}, 5)).hasSize(5);
    }

    @Test
    void vectorShardDeleteAndSearch() {
        VectorShardManager manager = new VectorShardManager(3);
        manager.put(new Embedding("a", new float[]{1, 0}));
        manager.delete("a");
        assertThat(manager.totalSize()).isZero();
        assertThat(manager.search(new float[]{1, 0}, 5)).isEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {5, 100})
    void rebalancePlanCounts(int count) {
        VectorShardManager manager = new VectorShardManager(2);
        for (int i = 0; i < count; i++) {
            manager.put(new Embedding("e" + i,
                    new float[]{1, 1}));
        }
        assertThat(manager.rebalance(3)).isGreaterThanOrEqualTo(0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
