package io.tieringkv.sql.distributed;

import io.tieringkv.sql.SqlEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 查询优化（Goal 7）：谓词下推与结果缓存。 */
class QueryOptimizationTest {

    @Test
    void predicatePushdownNarrowsPlans() {
        List<ShardPlan> plans = new ShardPlanner().plan(
                List.of("r1"), 8, "k");
        List<ShardPlan> narrowed = new PredicatePushdown().filter(
                plans, bytes("k000003"), bytes("k000004"));
        assertThat(narrowed).hasSize(1);
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {4, 16})
    void predicatePushdownShardCounts(int shards) {
        List<ShardPlan> plans = new ShardPlanner().plan(
                List.of("r1"), shards, "k");
        List<ShardPlan> narrowed = new PredicatePushdown().filter(
                plans, bytes("k000002"), bytes("k000003"));
        assertThat(narrowed).hasSize(1);
    }

    @Test
    void predicatePushdownNoRangeKeepsAll() {
        List<ShardPlan> plans = new ShardPlanner().plan(
                List.of("r1"), 4, "k");
        assertThat(new PredicatePushdown().filter(
                plans, null, null)).hasSize(4);
    }

    @Test
    void queryCacheHitOnSameWatermark() {
        QueryCache cache = new QueryCache();
        List<SqlEngine.Row> rows = List.of(
                new SqlEngine.Row(bytes("k"), bytes("v")));
        cache.put("q1", 100, rows);
        assertThat(cache.get("q1", 100)).hasSize(1);
    }

    @Test
    void queryCacheMissOnWatermarkChange() {
        QueryCache cache = new QueryCache();
        cache.put("q1", 100, List.of(
                new SqlEngine.Row(bytes("k"), bytes("v"))));
        assertThat(cache.get("q1", 101)).isNull();
    }

    @ParameterizedTest(name = "watermark {0}")
    @ValueSource(longs = {0, 50, Long.MAX_VALUE})
    void queryCacheWatermarkBoundaries(long watermark) {
        QueryCache cache = new QueryCache();
        cache.put("q", watermark, List.of());
        assertThat(cache.get("q", watermark)).isEmpty();
    }

    @Test
    void queryCacheInvalidate() {
        QueryCache cache = new QueryCache();
        cache.put("q1", 100, List.of(
                new SqlEngine.Row(bytes("k"), bytes("v"))));
        cache.invalidate("q1");
        assertThat(cache.get("q1", 100)).isNull();
        assertThat(cache.size()).isZero();
    }

    @Test
    void queryCacheSize() {
        QueryCache cache = new QueryCache();
        cache.put("q1", 1, List.of());
        cache.put("q2", 1, List.of());
        assertThat(cache.size()).isEqualTo(2);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
