package io.tieringkv.platform;

import io.tieringkv.cluster.scheduler.PlacementScheduler;
import io.tieringkv.cluster.scheduler.PlacementScheduler.Node;
import io.tieringkv.cluster.scheduler.QuotaScheduler;
import io.tieringkv.cluster.scheduler.RebalanceScheduler;
import io.tieringkv.compliance.KeyRotationManager;
import io.tieringkv.compliance.KeyRotationManager.SigningKey;
import io.tieringkv.datamesh.ObjectLifecycleManager;
import io.tieringkv.datamesh.ObjectLifecycleManager.LifecycleRule;
import io.tieringkv.datamesh.ObjectStorageArchive;
import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.datamesh.S3ObjectStorage;
import io.tieringkv.datamesh.S3ObjectStorage.S3Object;
import io.tieringkv.observability.cost.SpotMarketDataSource;
import io.tieringkv.observability.cost.SpotMarketFeed;
import io.tieringkv.storage.compaction.LeveledCompactionPlanner;
import io.tieringkv.storage.memory.ImmutableMemTableRotator;
import io.tieringkv.compliance.DataResidencyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 41 生产门禁（JVM 级）：S3/数据源/轮换/生命周期/LSM/PD。 */
class Phase41ProductionGateTest {

    @Test
    void s3FallbackGate() {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "");
        assertThat(storage.realEndpointConfigured()).isFalse();
        S3Object object = storage.put("obj-1",
                "data".getBytes(StandardCharsets.UTF_8), 1);
        assertThat(storage.get(object.key())).isPresent();
    }

    @Test
    void s3RealEndpointGate() {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "https://s3.example.com");
        assertThat(storage.realEndpointConfigured()).isTrue();
    }

    @Test
    void spotDataSourceFallbackGate() {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        var tick = source.fetch("aws-us", 1);
        assertThat(tick.cloud()).isEqualTo("aws-us");
        assertThat(source.lastFetch("aws-us")).contains(1L);
    }

    @Test
    void keyRotationGate() {
        KeyRotationManager manager = keyManager();
        manager.prepareNext(key("k2"));
        manager.rotate(1);
        assertThat(manager.active().keyId()).isEqualTo("k2");
        assertThat(manager.validates(key("k1"))).isTrue();
        manager.rollback();
        assertThat(manager.active().keyId()).isEqualTo("k1");
    }

    @Test
    void objectLifecycleGate() {
        ObjectLifecycleManager lifecycle =
                new ObjectLifecycleManager();
        lifecycle.addRule(new LifecycleRule("obj-", 7));
        ArchivedObject object = archived("obj-v1", 0);
        long day = 24 * 60 * 60 * 1000;
        assertThat(lifecycle.apply(object, 1)).isTrue();
        assertThat(lifecycle.expired(object, day * 10)).isTrue();
        lifecycle.protect("obj-v1");
        assertThat(lifecycle.isProtected("obj-v1")).isTrue();
    }

    @Test
    void leveledAndRotationGate() {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        var plan = planner.planLevel(200, 100, 64, 0);
        assertThat(plan.targetLevel()).isEqualTo(1);
        assertThat(plan.fileCount()).isEqualTo(4);
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        String active = rotator.activeId();
        rotator.rotate();
        assertThat(rotator.immutables()).contains(active);
        assertThat(rotator.flushDone(active)).isTrue();
    }

    @Test
    void pdSchedulersGate() {
        PlacementScheduler placement = new PlacementScheduler();
        placement.registerNode(new Node("n1", "az-1"));
        assertThat(placement.place("r1", "az-1", 0))
                .isEqualTo("n1");
        RebalanceScheduler rebalance = new RebalanceScheduler();
        assertThat(rebalance.plan(Map.of(
                "n1", 150L, "n2", 50L), 100)).hasSize(1);
        QuotaScheduler quota = new QuotaScheduler(1);
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isFalse();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedS3Keys(int count) {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "");
        for (int i = 0; i < count; i++) {
            storage.put("obj-" + i, new byte[8], i);
        }
        assertThat(storage.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "rotations {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRotations(int count) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < count; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.active().keyId())
                .isEqualTo("k" + (count + 1));
    }

    @ParameterizedTest(name = "bytes {0}")
    @ValueSource(longs = {0, 128, 256, 1000})
    void parameterizedLeveledBytes(long bytes) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        var plan = planner.planLevel(bytes, 500, 128, 1);
        assertThat(plan.sourceLevel()).isEqualTo(1);
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {1, 5, 20})
    void parameterizedQuotas(long quota) {
        QuotaScheduler scheduler = new QuotaScheduler(quota);
        for (int i = 0; i < quota; i++) {
            assertThat(scheduler.tryAcquire()).isTrue();
        }
        assertThat(scheduler.tryAcquire()).isFalse();
    }

    @Test
    void objectLifecycleConcurrentGate() throws Exception {
        ObjectLifecycleManager lifecycle =
                new ObjectLifecycleManager();
        lifecycle.addRule(new LifecycleRule("obj-", 30));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    lifecycle.apply(archived("obj-v" + i, 1), 2);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(lifecycle.applied()).hasSize(200);
    }

    private static KeyRotationManager keyManager() {
        return new KeyRotationManager(key("k1"));
    }

    private static SigningKey key(String keyId) {
        return new SigningKey(keyId,
                keyId.getBytes(StandardCharsets.UTF_8),
                KeyRotationManager.KeyStatus.ACTIVE);
    }

    private static ArchivedObject archived(String key,
                                           long time) {
        return new ArchivedObject(key, "aws-us",
                new RemoteSnapshot(key, "gcp-us", 1, 1, false,
                        time), time);
    }
}
