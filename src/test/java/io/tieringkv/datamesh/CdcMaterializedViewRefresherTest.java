package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.ChangeType;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.MaterializedViewManager.Definition;
import io.tieringkv.datamesh.MaterializedViewManager.Snapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CDC 增量物化（ADR-0166）：插入/更新/删除 + 回退。 */
class CdcMaterializedViewRefresherTest {

    private MaterializedViewManager manager;
    private CdcMaterializedViewRefresher refresher;

    @BeforeEach
    void setUp() {
        manager = new MaterializedViewManager(
                new CloudFederatedExecutor(new ComplianceValidator(),
                        new DataResidencyPolicy(Map.of(
                                "aws-us", "us"))));
        manager.create(new Definition("v1", List.of(
                new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM, 60_000));
        refresher = new CdcMaterializedViewRefresher();
    }

    @Test
    void insertUpdatesSum() {
        assertThat(refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10))).isTrue();
        assertThat(manager.query("v1").value()).isEqualTo(10);
        assertThat(refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k2", ChangeType.INSERT, 5))).isTrue();
        assertThat(manager.query("v1").value()).isEqualTo(15);
    }

    @Test
    void updateReplacesValue() {
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10));
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.UPDATE, 20));
        assertThat(manager.query("v1").value()).isEqualTo(20);
    }

    @Test
    void deleteRemovesValue() {
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10));
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k2", ChangeType.INSERT, 5));
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.DELETE, 0));
        assertThat(manager.query("v1").value()).isEqualTo(5);
        assertThat(refresher.trackedKeys("v1")).isEqualTo(1);
    }

    @Test
    void deleteUnknownKeyIgnored() {
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10));
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("missing", ChangeType.DELETE, 0));
        assertThat(manager.query("v1").value()).isEqualTo(10);
    }

    @Test
    void emptyViewAggregateZero() {
        Snapshot snapshot = manager.query("v1");
        assertThat(snapshot.value()).isZero();
    }

    @Test
    void countAggregateTracksKeys() {
        refresher.apply(manager, "v1", Aggregate.COUNT,
                new CdcChange("k1", ChangeType.INSERT, 0));
        refresher.apply(manager, "v1", Aggregate.COUNT,
                new CdcChange("k2", ChangeType.INSERT, 0));
        assertThat(manager.query("v1").value()).isEqualTo(2);
        refresher.apply(manager, "v1", Aggregate.COUNT,
                new CdcChange("k1", ChangeType.DELETE, 0));
        assertThat(manager.query("v1").value()).isEqualTo(1);
    }

    @Test
    void avgAggregate() {
        refresher.apply(manager, "v1", Aggregate.AVG,
                new CdcChange("k1", ChangeType.INSERT, 10));
        refresher.apply(manager, "v1", Aggregate.AVG,
                new CdcChange("k2", ChangeType.INSERT, 30));
        assertThat(manager.query("v1").value()).isEqualTo(20);
        refresher.apply(manager, "v1", Aggregate.AVG,
                new CdcChange("k1", ChangeType.DELETE, 0));
        assertThat(manager.query("v1").value()).isEqualTo(30);
    }

    @Test
    void minMaxAggregates() {
        refresher.apply(manager, "v1", Aggregate.MIN,
                new CdcChange("k1", ChangeType.INSERT, 10));
        refresher.apply(manager, "v1", Aggregate.MIN,
                new CdcChange("k2", ChangeType.INSERT, 3));
        assertThat(manager.query("v1").value()).isEqualTo(3);
        refresher.apply(manager, "v1", Aggregate.MAX,
                new CdcChange("k2", ChangeType.INSERT, 3));
        assertThat(manager.query("v1").value()).isEqualTo(10);
    }

    @Test
    void snapshotMarkedFresh() {
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10));
        assertThat(manager.isStale("v1")).isFalse();
    }

    @Test
    void failureInvalidatesAndClears() {
        manager.invalidate("v1");
        assertThat(refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10))).isTrue();
        assertThat(refresher.trackedKeys("v1")).isEqualTo(1);
        assertThat(refresher.apply(manager, "missing",
                Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10)))
                .isFalse();
        assertThat(manager.viewIds()).doesNotContain("missing");
    }

    @Test
    void refreshFullClearsIncrementalState() {
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.INSERT, 10));
        refresher.refreshFull(manager, "v1", "aws-us",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 42, 1));
        assertThat(manager.query("v1").value()).isEqualTo(42);
        assertThat(refresher.trackedKeys("v1")).isZero();
    }

    @Test
    void blankKeyRejected() {
        assertThatThrownBy(() -> new CdcChange("",
                ChangeType.INSERT, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTypeRejected() {
        assertThatThrownBy(() -> new CdcChange("k1", null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedAggregates(String aggregate) {
        refresher.apply(manager, "v1",
                Aggregate.valueOf(aggregate),
                new CdcChange("k1", ChangeType.INSERT, 4));
        refresher.apply(manager, "v1",
                Aggregate.valueOf(aggregate),
                new CdcChange("k2", ChangeType.INSERT, 8));
        Snapshot snapshot = manager.query("v1");
        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.count()).isPositive();
    }

    @ParameterizedTest(name = "changes {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedChangeVolumes(int count) {
        for (int i = 0; i < count; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k" + i, ChangeType.INSERT, 1));
        }
        assertThat(manager.query("v1").value()).isEqualTo(count);
        assertThat(refresher.trackedKeys("v1")).isEqualTo(count);
    }

    @ParameterizedTest(name = "sequence {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedInsertUpdateDelete(int rounds) {
        for (int i = 0; i < rounds; i++) {
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.INSERT, i));
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.UPDATE, i + 1));
            refresher.apply(manager, "v1", Aggregate.SUM,
                    new CdcChange("k", ChangeType.DELETE, 0));
        }
        assertThat(manager.query("v1").value()).isZero();
        assertThat(refresher.trackedKeys("v1")).isZero();
    }

    @Test
    void concurrentAppliesStable() throws Exception {
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    refresher.apply(manager, "v1", Aggregate.SUM,
                            new CdcChange("k" + (i % 10),
                                    ChangeType.INSERT, 1));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(refresher.trackedKeys("v1")).isEqualTo(10);
        assertThat(manager.query("v1").value()).isEqualTo(10);
    }

    @Test
    void updateUnknownKeyInserts() {
        refresher.apply(manager, "v1", Aggregate.SUM,
                new CdcChange("k1", ChangeType.UPDATE, 7));
        assertThat(manager.query("v1").value()).isEqualTo(7);
    }
}
