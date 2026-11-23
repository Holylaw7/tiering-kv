package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.ChangeType;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudResult;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteDefinition;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨云远端物化（ADR-0173）：远端落盘 + 增量同步 + 主权。 */
class RemoteMaterializationManagerTest {

    private RemoteMaterializationManager manager;

    @BeforeEach
    void setUp() {
        manager = new RemoteMaterializationManager(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us",
                        "aws-eu", "eu")));
    }

    @Test
    void defineCreatesStaleSnapshot() {
        manager.define(def("v1", "gcp-us", 2));
        RemoteSnapshot snapshot = manager.snapshot("v1");
        assertThat(snapshot.stale()).isTrue();
        assertThat(snapshot.remoteCloud()).isEqualTo("gcp-us");
        assertThat(manager.isStale("v1")).isTrue();
    }

    @Test
    void syncInsertUpdatesRemote() {
        manager.define(def("v1", "gcp-us", 2));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.INSERT, 10));
        manager.syncChange("v1",
                new CdcChange("k2", ChangeType.INSERT, 5));
        RemoteSnapshot snapshot = manager.snapshot("v1");
        assertThat(snapshot.value()).isEqualTo(15);
        assertThat(snapshot.stale()).isFalse();
    }

    @Test
    void syncUpdateAndDelete() {
        manager.define(def("v1", "gcp-us", 2));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.INSERT, 10));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.UPDATE, 20));
        manager.syncChange("v1",
                new CdcChange("k2", ChangeType.INSERT, 5));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.DELETE, 0));
        assertThat(manager.snapshot("v1").value()).isEqualTo(5);
    }

    @Test
    void refreshFullClearsIncremental() {
        manager.define(def("v1", "gcp-us", 2));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.INSERT, 10));
        RemoteSnapshot snapshot = manager.refreshFull("v1",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(),
                        shard.domainId().equals("d0") ? 30 : 12,
                        1));
        assertThat(snapshot.value()).isEqualTo(42);
        assertThat(snapshot.stale()).isFalse();
    }

    @Test
    void crossResidencyDefineRejected() {
        assertThatThrownBy(() -> manager.define(new RemoteDefinition(
                "v1", "gcp-us", "aws-us",
                List.of(new CloudShard("d1", "aws-eu", "m")),
                Aggregate.SUM)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void crossResidencyRemoteCloudRejected() {
        assertThatThrownBy(() -> manager.define(new RemoteDefinition(
                "v1", "aws-eu", "aws-us",
                List.of(new CloudShard("d1", "aws-us", "m")),
                Aggregate.SUM)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void unknownViewRejected() {
        assertThatThrownBy(() -> manager.snapshot("missing"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.syncChange("missing",
                new CdcChange("k", ChangeType.INSERT, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateDefineRejected() {
        manager.define(def("v1", "gcp-us", 2));
        assertThatThrownBy(() -> manager.define(
                def("v1", "gcp-us", 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidateMarksStale() {
        manager.define(def("v1", "gcp-us", 2));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.INSERT, 10));
        manager.invalidate("v1");
        assertThat(manager.isStale("v1")).isTrue();
        assertThat(manager.snapshot("v1").value()).isEqualTo(10);
    }

    @Test
    void listViewsAndSize() {
        manager.define(def("v1", "gcp-us", 2));
        manager.define(def("v2", "aws-us", 1));
        assertThat(manager.viewIds()).containsExactlyInAnyOrder(
                "v1", "v2");
        assertThat(manager.size()).isEqualTo(2);
    }

    @Test
    void blankViewIdRejected() {
        assertThatThrownBy(() -> new RemoteDefinition("", "gcp-us",
                "aws-us", List.of(new CloudShard("d", "aws-us",
                        "m")), Aggregate.SUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDefinitionRejected() {
        assertThatThrownBy(() -> manager.define(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyShardsRejected() {
        assertThatThrownBy(() -> new RemoteDefinition("v1",
                "gcp-us", "aws-us", List.of(), Aggregate.SUM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "aggregate {0}")
    @ValueSource(strings = {"SUM", "COUNT", "AVG", "MIN", "MAX"})
    void parameterizedAggregates(String aggregate) {
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", shards(2),
                Aggregate.valueOf(aggregate)));
        manager.syncChange("v1",
                new CdcChange("k1", ChangeType.INSERT, 4));
        manager.syncChange("v1",
                new CdcChange("k2", ChangeType.INSERT, 8));
        RemoteSnapshot snapshot = manager.snapshot("v1");
        assertThat(snapshot.stale()).isFalse();
        assertThat(snapshot.count()).isPositive();
    }

    @ParameterizedTest(name = "shards {0}")
    @ValueSource(ints = {1, 2, 5})
    void parameterizedShardCounts(int count) {
        manager.define(new RemoteDefinition("v1", "gcp-us",
                "aws-us", shards(count), Aggregate.SUM));
        RemoteSnapshot snapshot = manager.refreshFull("v1",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 2, 1));
        assertThat(snapshot.value()).isEqualTo(2L * count);
    }

    @ParameterizedTest(name = "changes {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedChangeVolumes(int count) {
        manager.define(def("v1", "gcp-us", 2));
        for (int i = 0; i < count; i++) {
            manager.syncChange("v1",
                    new CdcChange("k" + i, ChangeType.INSERT, 1));
        }
        assertThat(manager.snapshot("v1").value())
                .isEqualTo(count);
    }

    @Test
    void concurrentSyncAndRefreshStable() throws Exception {
        manager.define(def("v1", "gcp-us", 2));
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                manager.syncChange("v1",
                        new CdcChange("k" + (i % 20),
                                ChangeType.INSERT, 1));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                manager.snapshot("v1");
                manager.isStale("v1");
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(manager.snapshot("v1").value()).isEqualTo(20);
    }

    @Test
    void remoteCloudCarriedInSnapshot() {
        manager.define(def("v1", "gcp-us", 2));
        assertThat(manager.snapshot("v1").remoteCloud())
                .isEqualTo("gcp-us");
    }

    @Test
    void syncAfterFullRefreshBuildsNewState() {
        manager.define(def("v1", "gcp-us", 2));
        manager.refreshFull("v1",
                shard -> new CloudResult(shard.domainId(),
                        shard.cloud(), 5, 1));
        manager.syncChange("v1",
                new CdcChange("new", ChangeType.INSERT, 7));
        assertThat(manager.snapshot("v1").value()).isEqualTo(7);
    }

    private static RemoteDefinition def(String viewId,
                                        String remoteCloud,
                                        int shardCount) {
        return new RemoteDefinition(viewId, remoteCloud, "aws-us",
                shards(shardCount), Aggregate.SUM);
    }

    private static List<CloudShard> shards(int count) {
        List<CloudShard> shards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            shards.add(new CloudShard("d" + i, "aws-us", "m"));
        }
        return shards;
    }
}
