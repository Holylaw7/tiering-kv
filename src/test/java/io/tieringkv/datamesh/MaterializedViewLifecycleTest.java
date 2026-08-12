package io.tieringkv.datamesh;

import io.tieringkv.compliance.ComplianceValidator;
import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.CdcChange;
import io.tieringkv.datamesh.CdcMaterializedViewRefresher.ChangeType;
import io.tieringkv.datamesh.CloudFederatedExecutor.Aggregate;
import io.tieringkv.datamesh.CloudFederatedExecutor.CloudShard;
import io.tieringkv.datamesh.MaterializedViewLifecycle.ArchivedView;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteDefinition;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 物化视图生命周期（ADR-0181）：TTL + 归档恢复。 */
class MaterializedViewLifecycleTest {

    private final MaterializedViewLifecycle lifecycle =
            new MaterializedViewLifecycle();

    @Test
    void expiredWhenPastTtl() {
        RemoteSnapshot snapshot = snapshot(1000);
        assertThat(lifecycle.expired(snapshot, 5000, 7000)).isTrue();
        assertThat(lifecycle.expired(snapshot, 5000, 6000))
                .isFalse();
    }

    @Test
    void zeroTtlExpiresImmediatelyAfterRefresh() {
        RemoteSnapshot snapshot = snapshot(1000);
        assertThat(lifecycle.expired(snapshot, 0, 1001)).isTrue();
    }

    @Test
    void archiveRestoreRoundTrip() {
        RemoteSnapshot snapshot = snapshot(1000);
        ArchivedView archived = lifecycle.archive(snapshot, 2000);
        assertThat(archived.archivedAtMillis()).isEqualTo(2000);
        RemoteSnapshot restored = lifecycle.restore(archived);
        assertThat(restored).isEqualTo(snapshot);
    }

    @Test
    void nullSnapshotRejected() {
        assertThatThrownBy(() -> lifecycle.expired(null, 1000, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lifecycle.archive(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeTtlRejected() {
        assertThatThrownBy(() -> lifecycle.expired(
                snapshot(1), -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullArchivedRejected() {
        assertThatThrownBy(() -> lifecycle.restore(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sweepFindsExpiredViews() {
        RemoteMaterializationManager manager = manager();
        manager.define(new RemoteDefinition("fresh", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        manager.define(new RemoteDefinition("stale", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        manager.syncChange("fresh",
                new CdcChange("k", ChangeType.INSERT, 1));
        List<String> expired = lifecycle.sweep(manager, 5000,
                System.currentTimeMillis() + 1000);
        assertThat(expired).containsExactly("stale");
    }

    @Test
    void sweepNoExpiredWhenFresh() {
        RemoteMaterializationManager manager = manager();
        manager.define(new RemoteDefinition("fresh", "gcp-us",
                "aws-us", List.of(new CloudShard("d1", "aws-us",
                        "m")), Aggregate.SUM));
        manager.syncChange("fresh",
                new CdcChange("k", ChangeType.INSERT, 1));
        assertThat(lifecycle.sweep(manager, 60_000,
                System.currentTimeMillis() + 1000)).isEmpty();
    }

    @Test
    void nullManagerRejected() {
        assertThatThrownBy(() -> lifecycle.sweep(null, 1000, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(longs = {0, 1000, 60_000})
    void parameterizedTtls(long ttl) {
        RemoteSnapshot snapshot = snapshot(1000);
        assertThat(lifecycle.expired(snapshot, ttl,
                1000 + ttl)).isFalse();
        assertThat(lifecycle.expired(snapshot, ttl,
                1000 + ttl + 1)).isTrue();
    }

    @ParameterizedTest(name = "views {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedSweepCounts(int count) {
        RemoteMaterializationManager manager = manager();
        for (int i = 0; i < count; i++) {
            manager.define(new RemoteDefinition("v" + i, "gcp-us",
                    "aws-us", List.of(new CloudShard("d1",
                            "aws-us", "m")), Aggregate.SUM));
        }
        List<String> expired = lifecycle.sweep(manager, 0,
                System.currentTimeMillis() + 1);
        assertThat(expired).hasSize(count);
    }

    @Test
    void archiveCarriesStaleFlag() {
        RemoteSnapshot stale = new RemoteSnapshot("v1", "gcp-us",
                5, 1, true, 1000);
        ArchivedView archived = lifecycle.archive(stale, 2000);
        assertThat(archived.stale()).isTrue();
        assertThat(lifecycle.restore(archived).stale()).isTrue();
    }

    private static RemoteSnapshot snapshot(long refreshedAt) {
        return new RemoteSnapshot("v1", "gcp-us", 10, 2, false,
                refreshedAt);
    }

    private static RemoteMaterializationManager manager() {
        return new RemoteMaterializationManager(
                new ComplianceValidator(),
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")));
    }
}
