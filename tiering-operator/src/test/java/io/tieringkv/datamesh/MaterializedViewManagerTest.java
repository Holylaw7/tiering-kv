package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.MaterializedViewManager.Definition;
import io.tieringkv.datamesh.MaterializedViewManager.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云物化视图（ADR-0158）：创建/刷新/失效/查询。 */
class MaterializedViewManagerTest {

    private MaterializedViewManager manager;

    @BeforeEach
    void setUp() {
        manager = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-us", "us"))));
    }

    @Test
    void createThenQueryStale() {
        manager.create(def("v1", 2, 60_000));
        Snapshot snapshot = manager.query("v1");
        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.value()).isZero();
    }

    @Test
    void refreshMarksFresh() {
        manager.create(def("v1", 2, 60_000));
        Snapshot snapshot = manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.value()).isEqualTo(10);
        assertThat(snapshot.count()).isEqualTo(2);
    }

    @Test
    void invalidateMarksStale() {
        manager.create(def("v1", 2, 60_000));
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        Snapshot snapshot = manager.invalidate("v1");
        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.value()).isEqualTo(10);
        assertThat(manager.isStale("v1")).isTrue();
    }

    @Test
    void queryUnknownViewRejected() {
        assertThatThrownBy(() -> manager.query("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDuplicateRejected() {
        manager.create(def("v1", 2, 60_000));
        assertThatThrownBy(() -> manager.create(
                def("v1", 2, 60_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshUnknownViewRejected() {
        assertThatThrownBy(() -> manager.refresh("missing",
                "aws-us", shard -> result(shard, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshIfDueWithinPeriodSkips() {
        manager.create(def("v1", 2, Long.MAX_VALUE));
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        assertThat(manager.refreshIfDue("v1", "aws-us",
                shard -> result(shard, 9, 1))).isFalse();
        assertThat(manager.query("v1").value()).isEqualTo(10);
    }

    @Test
    void refreshIfDuePeriodZeroAlwaysRefreshes() {
        manager.create(def("v1", 2, 0));
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        assertThat(manager.refreshIfDue("v1", "aws-us",
                shard -> result(shard, 9, 1))).isTrue();
        assertThat(manager.query("v1").value()).isEqualTo(18);
    }

    @Test
    void refreshIfDueOnStaleRefreshes() {
        manager.create(def("v1", 2, Long.MAX_VALUE));
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        manager.invalidate("v1");
        assertThat(manager.refreshIfDue("v1", "aws-us",
                shard -> result(shard, 3, 1))).isTrue();
        assertThat(manager.isStale("v1")).isFalse();
    }

    @Test
    void listViewsAndSize() {
        manager.create(def("v1", 2, 60_000));
        manager.create(def("v2", 1, 60_000));
        assertThat(manager.viewIds()).containsExactlyInAnyOrder(
                "v1", "v2");
        assertThat(manager.size()).isEqualTo(2);
    }

    @Test
    void dropRemovesView() {
        manager.create(def("v1", 2, 60_000));
        manager.drop("v1");
        assertThat(manager.size()).isZero();
        assertThatThrownBy(() -> manager.query("v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankViewIdRejected() {
        assertThatThrownBy(() -> new Definition("", List.of(
                new CloudShard("d", "aws-us", "m")),
                Aggregate.SUM, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyShardsRejected() {
        assertThatThrownBy(() -> new Definition("v", List.of(),
                Aggregate.SUM, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativePeriodRejected() {
        assertThatThrownBy(() -> new Definition("v", List.of(
                new CloudShard("d", "aws-us", "m")),
                Aggregate.SUM, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDefinitionRejected() {
        assertThatThrownBy(() -> manager.create(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotIsValueSnapshot() {
        manager.create(def("v1", 2, 60_000));
        Snapshot before = manager.query("v1");
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        assertThat(before.stale()).isTrue();
        assertThat(manager.query("v1").stale()).isFalse();
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedAggregates(String aggregate) {
        manager.create(new Definition("v1", shards(2),
                Aggregate.valueOf(aggregate), 60_000));
        Snapshot snapshot = manager.refresh("v1", "aws-us",
                shard -> result(shard, 4, 1));
        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.count()).isPositive();
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 3, 8})
    void parameterizedShardCounts(int count) {
        manager.create(new Definition("v1", shards(count),
                Aggregate.SUM, 60_000));
        Snapshot snapshot = manager.refresh("v1", "aws-us",
                shard -> result(shard, 1, 1));
        assertThat(snapshot.value()).isEqualTo(count);
    }

    @ParameterizedTest(name = "period {0}")
    @ValueSource(longs = {0, 1, 60_000})
    void parameterizedPeriods(long period) {
        manager.create(def("v1", 2, period));
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        boolean refreshed = manager.refreshIfDue("v1", "aws-us",
                shard -> result(shard, 9, 1));
        assertThat(refreshed).isEqualTo(period == 0);
    }

    @Test
    void concurrentRefreshAndQuery() throws Exception {
        manager.create(def("v1", 2, 0));
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                manager.refresh("v1", "aws-us",
                        shard -> result(shard, 5, 1));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                manager.query("v1");
                manager.isStale("v1");
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(manager.query("v1").value()).isEqualTo(10);
    }

    @Test
    void refreshFailureKeepsPriorSnapshot() {
        manager.create(def("v1", 2, 60_000));
        manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        assertThatThrownBy(() -> manager.refresh("v1", "aws-us",
                shard -> {
                    throw new IllegalStateException("io failure");
                })).isInstanceOf(IllegalStateException.class);
        assertThat(manager.query("v1").value()).isEqualTo(10);
        assertThat(manager.query("v1").stale()).isFalse();
    }

    @Test
    void invalidationPreservesRefreshedAt() {
        manager.create(def("v1", 2, 60_000));
        Snapshot fresh = manager.refresh("v1", "aws-us",
                shard -> result(shard, 5, 1));
        Snapshot invalidated = manager.invalidate("v1");
        assertThat(invalidated.refreshedAtMillis())
                .isEqualTo(fresh.refreshedAtMillis());
    }

    @Test
    void crossResidencyViewRefreshRejected() {
        MaterializedViewManager local = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us", "gcp-eu", "eu"))));
        local.create(new Definition("v1", List.of(
                new CloudShard("a", "aws-us", "m"),
                new CloudShard("b", "gcp-eu", "m")),
                Aggregate.SUM, 60_000));
        assertThatThrownBy(() -> local.refresh("v1", "aws-us",
                shard -> result(shard, 1, 1)))
                .isInstanceOf(SecurityException.class);
    }

    private static Definition def(String viewId, int shardCount,
                                  long period) {
        return new Definition(viewId, shards(shardCount),
                Aggregate.SUM, period);
    }

    private static List<CloudShard> shards(int count) {
        List<CloudShard> shards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            shards.add(new CloudShard("d" + i,
                    i % 2 == 0 ? "aws-us" : "gcp-us", "m"));
        }
        return shards;
    }

    private static CloudResult result(CloudShard shard,
                                      double value, long count) {
        return new CloudResult(shard.domainId(), shard.cloud(),
                value, count);
    }
}
