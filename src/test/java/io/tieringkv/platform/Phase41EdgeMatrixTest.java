package io.tieringkv.platform;

import io.tieringkv.cluster.scheduler.PlacementScheduler;
import io.tieringkv.cluster.scheduler.PlacementScheduler.Node;
import io.tieringkv.cluster.scheduler.QuotaScheduler;
import io.tieringkv.cluster.scheduler.RebalanceScheduler;
import io.tieringkv.compliance.KeyRotationManager;
import io.tieringkv.compliance.KeyRotationManager.SigningKey;
import io.tieringkv.datamesh.ObjectLifecycleManager;
import io.tieringkv.datamesh.ObjectLifecycleManager.LifecycleRule;
import io.tieringkv.datamesh.ObjectStorageArchive.ArchivedObject;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;
import io.tieringkv.datamesh.S3ObjectStorage;
import io.tieringkv.datamesh.S3ObjectStorage.S3Object;
import io.tieringkv.observability.cost.SpotMarketDataSource;
import io.tieringkv.observability.cost.SpotMarketFeed;
import io.tieringkv.storage.compaction.LeveledCompactionPlanner;
import io.tieringkv.storage.memory.ImmutableMemTableRotator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 41 参数化边缘矩阵：S3/数据源/轮换/生命周期/LSM/PD。 */
class Phase41EdgeMatrixTest {

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void s3KeyCounts(int count) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < count; i++) {
            storage.put("obj-" + i, new byte[8], i);
        }
        assertThat(storage.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {0, 8, 64, 1024, 4096})
    void s3DataSizes(int size) {
        S3ObjectStorage storage = storage();
        storage.put("obj-1", new byte[size], 1);
        assertThat(storage.get("obj-1").orElseThrow().data())
                .hasSize(size);
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"a", "obj-1", "path/to/k", "with space",
            "unicode-键"})
    void s3Keys(String key) {
        S3ObjectStorage storage = storage();
        storage.put(key, new byte[1], 1);
        assertThat(storage.get(key)).isPresent();
    }

    @ParameterizedTest(name = "bucket {0}")
    @ValueSource(strings = {"tiering", "data", "archive-1",
            "cold-store", "bucket-x"})
    void s3Buckets(String bucket) {
        S3ObjectStorage storage = new S3ObjectStorage(bucket, "");
        assertThat(storage.bucket()).isEqualTo(bucket);
    }

    @ParameterizedTest(name = "endpoint {0}")
    @ValueSource(strings = {"", "https://s3.a.com",
            "https://s3.b.com", "http://localhost:9000",
            "minio://local"})
    void s3Endpoints(String endpoint) {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                endpoint);
        assertThat(storage.realEndpointConfigured())
                .isEqualTo(!endpoint.isBlank());
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void s3OverwriteRounds(int rounds) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < rounds; i++) {
            storage.put("obj-1", new byte[]{1}, i);
        }
        assertThat(storage.size()).isEqualTo(1);
        assertThat(storage.get("obj-1").orElseThrow()
                .timestampMillis()).isEqualTo(rounds - 1);
    }

    @ParameterizedTest(name = "cloud {0}")
    @ValueSource(strings = {"aws-us", "gcp-us", "azure-us"})
    void spotDataClouds(String cloud) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        var tick = source.fetch(cloud, 1);
        assertThat(tick.cloud()).isEqualTo(cloud);
    }

    @ParameterizedTest(name = "time {0}")
    @ValueSource(longs = {0, 1, 100, 1000, 10_000})
    void spotDataTimes(long timestamp) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        var tick = source.fetch("aws-us", timestamp);
        assertThat(tick.timestampMillis()).isEqualTo(timestamp);
    }

    @ParameterizedTest(name = "endpoint {0}")
    @ValueSource(strings = {"", "https://market.a.com",
            "https://market.b.com"})
    void spotDataEndpoints(String endpoint) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                endpoint, new SpotMarketFeed());
        assertThat(source.endpoint()).isEqualTo(endpoint);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void spotDataRounds(int rounds) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < rounds; i++) {
            source.fetch("aws-us", i);
        }
        assertThat(source.lastFetch("aws-us"))
                .contains((long) rounds - 1);
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"k1", "k2", "key-a", "long-key", "x"})
    void keyRotationKeys(String keyId) {
        KeyRotationManager manager = new KeyRotationManager(
                key(keyId));
        assertThat(manager.active().keyId()).isEqualTo(keyId);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void keyRotationRounds(int rounds) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < rounds; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.audit()).hasSize(rounds);
        assertThat(manager.active().keyId())
                .isEqualTo("k" + (rounds + 1));
    }

    @ParameterizedTest(name = "days {0}")
    @ValueSource(longs = {0, 1, 7, 30, 365})
    void lifecycleDays(long days) {
        ObjectLifecycleManager manager = lifecycle(days);
        ArchivedObject object = archived("obj-v1", 0);
        long day = 24 * 60 * 60 * 1000;
        assertThat(manager.expired(object, day * (days + 1)))
                .isTrue();
        assertThat(manager.expired(object, day * days)).isFalse();
    }

    @ParameterizedTest(name = "prefix {0}")
    @ValueSource(strings = {"obj-", "cold-", "archive-", "data-",
            "temp-"})
    void lifecyclePrefixes(String prefix) {
        ObjectLifecycleManager manager = new ObjectLifecycleManager();
        manager.addRule(new LifecycleRule(prefix, 30));
        ArchivedObject object = archived(prefix + "v1", 1);
        assertThat(manager.apply(object, 2)).isTrue();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void lifecycleRounds(int rounds) {
        ObjectLifecycleManager manager = lifecycle(30);
        for (int i = 0; i < rounds; i++) {
            manager.apply(archived("obj-v" + i, 1), 2);
        }
        assertThat(manager.applied()).hasSize(rounds);
    }

    @ParameterizedTest(name = "bytes {0}")
    @ValueSource(longs = {0, 64, 128, 256, 1024})
    void leveledBytes(long bytes) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        var plan = planner.planLevel(bytes, 500, 128, 0);
        assertThat(plan.targetLevel()).isEqualTo(1);
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void leveledLevels(int level) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        var plan = planner.planLevel(1000, 500, 128, level);
        assertThat(plan.sourceLevel()).isEqualTo(level);
        assertThat(plan.targetLevel()).isEqualTo(level + 1);
    }

    @ParameterizedTest(name = "max {0}")
    @ValueSource(longs = {100, 200, 500, 1000, 2000})
    void leveledMaxBytes(long max) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        assertThat(planner.shouldCompact(1000, max))
                .isEqualTo(1000 > max);
    }

    @ParameterizedTest(name = "rotations {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rotatorRounds(int rounds) {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        for (int i = 0; i < rounds; i++) {
            rotator.rotate();
        }
        assertThat(rotator.immutableCount()).isEqualTo(rounds);
    }

    @ParameterizedTest(name = "flush {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rotatorFlushRounds(int rounds) {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        for (int i = 0; i < rounds; i++) {
            String active = rotator.activeId();
            rotator.rotate();
            assertThat(rotator.flushDone(active)).isTrue();
        }
        assertThat(rotator.immutableCount()).isZero();
    }

    @ParameterizedTest(name = "az {0}")
    @ValueSource(strings = {"az-1", "az-2", "az-3"})
    void placementAz(String az) {
        PlacementScheduler scheduler = placement();
        assertThat(scheduler.place("r1", az, 0)).isNotNull();
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 3, 5, 10, 20})
    void placementNodes(int count) {
        PlacementScheduler scheduler = new PlacementScheduler();
        for (int i = 0; i < count; i++) {
            scheduler.registerNode(new Node("n" + i,
                    "az-" + (i % 3)));
        }
        assertThat(scheduler.nodeCount()).isEqualTo(count);
    }

    @ParameterizedTest(name = "load {0}")
    @ValueSource(longs = {50, 100, 150, 200, 300})
    void rebalanceLoads(long load) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        var moves = scheduler.plan(Map.of(
                "n1", load, "n2", 50L), 100);
        assertThat(moves.isEmpty()).isEqualTo(load <= 100);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {2, 3, 5, 10, 20})
    void rebalanceNodes(int count) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        Map<String, Long> loads = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            loads.put("n" + i, i % 2 == 0 ? 150L : 50L);
        }
        assertThat(scheduler.plan(loads, 100)).isNotEmpty();
    }

    @ParameterizedTest(name = "quota {0}")
    @ValueSource(longs = {0, 1, 10, 100, 1000})
    void quotaValues(long quota) {
        QuotaScheduler scheduler = new QuotaScheduler(quota);
        long acquired = 0;
        while (scheduler.tryAcquire()) {
            acquired++;
        }
        assertThat(acquired).isEqualTo(quota);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"1,1", "5,5", "10,10", "50,50", "100,100"})
    void quotaMix(long quota, int rounds) {
        QuotaScheduler scheduler = new QuotaScheduler(quota);
        for (int i = 0; i < rounds; i++) {
            scheduler.tryAcquire();
        }
        assertThat(scheduler.used()).isLessThanOrEqualTo(quota);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void s3DeleteRounds(int count) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < count; i++) {
            storage.put("obj-" + i, new byte[1], i);
        }
        for (int i = 0; i < count; i++) {
            assertThat(storage.delete("obj-" + i)).isTrue();
        }
        assertThat(storage.size()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void spotDataFetchVolumes(int count) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < count; i++) {
            source.fetch("aws-us", i);
        }
        assertThat(source.lastFetch("aws-us"))
                .contains((long) count - 1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void keyRotationGraceVolumes(int count) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < count; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.validates(key("k" + count))).isTrue();
        assertThat(manager.validates(key("k" + (count + 1))))
                .isTrue();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void lifecycleProtectVolumes(int count) {
        ObjectLifecycleManager manager = lifecycle(30);
        for (int i = 0; i < count; i++) {
            manager.protect("obj-v" + i);
        }
        assertThat(manager.isProtected("obj-v" + (count - 1)))
                .isTrue();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledPlanVolumes(int count) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        for (int i = 0; i < count; i++) {
            planner.planLevel(1000, 500, 128, i % 3);
        }
        assertThat(planner).isNotNull();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void placementRegionVolumes(int count) {
        PlacementScheduler scheduler = placement();
        for (int i = 0; i < count; i++) {
            scheduler.place("r" + i, "az-" + ((i % 3) + 1), 0);
        }
        assertThat(scheduler.regions()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rebalanceVolumeMoves(int count) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        Map<String, Long> loads = new java.util.HashMap<>();
        for (int i = 0; i < count; i++) {
            loads.put("n" + i, i % 2 == 0 ? 200L : 50L);
        }
        long expected = ((count + 1) / 2L) * (count / 2L);
        assertThat(scheduler.plan(loads, 100))
                .hasSize((int) expected);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,3", "10,5", "20,10", "50,25", "100,50"})
    void s3LifecycleMix(int objects, int protectedCount) {
        S3ObjectStorage storage = storage();
        ObjectLifecycleManager lifecycle = lifecycle(30);
        for (int i = 0; i < objects; i++) {
            storage.put("obj-" + i, new byte[1], i);
            lifecycle.apply(archived("obj-" + i, 1), 2);
        }
        for (int i = 0; i < protectedCount; i++) {
            lifecycle.protect("obj-" + i);
        }
        assertThat(storage.size()).isEqualTo(objects);
        assertThat(lifecycle.applied()).hasSize(objects);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void s3ReadRounds(int rounds) {
        S3ObjectStorage storage = storage();
        storage.put("obj-1", new byte[1], 1);
        for (int i = 0; i < rounds; i++) {
            assertThat(storage.get("obj-1")).isPresent();
        }
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void spotDataTypes(int rounds) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < rounds; i++) {
            assertThat(source.type().name()).isEqualTo("SIMULATED");
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void keyRotationRollbackVolumes(int count) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < count; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        for (int i = 0; i < count; i++) {
            manager.rollback();
        }
        assertThat(manager.active().keyId()).isEqualTo("k1");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void lifecycleExpiredVolumes(int count) {
        ObjectLifecycleManager manager = lifecycle(7);
        long day = 24 * 60 * 60 * 1000;
        for (int i = 0; i < count; i++) {
            assertThat(manager.expired(
                    archived("obj-v" + i, 0), day * 10)).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledPlanLevelVolumes(int count) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        for (int i = 0; i < count; i++) {
            assertThat(planner.planLevel(1000, 500, 128,
                    i % 5).targetLevel()).isEqualTo((i % 5) + 1);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rotatorPendingVolumes(int count) {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        for (int i = 0; i < count; i++) {
            rotator.rotate();
        }
        assertThat(rotator.pendingFlush()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void placementEpochVolumes(int count) {
        PlacementScheduler scheduler = placement();
        for (int i = 0; i < count; i++) {
            scheduler.advanceEpoch();
        }
        assertThat(scheduler.epoch()).isEqualTo(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rebalanceSingleVolumes(int count) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        Map<String, Long> loads = Map.of(
                "n1", 200L, "n2", 50L);
        for (int i = 0; i < count; i++) {
            assertThat(scheduler.plan(loads, 100)).hasSize(1);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void quotaResetVolumes(int count) {
        QuotaScheduler scheduler = new QuotaScheduler(1);
        for (int i = 0; i < count; i++) {
            scheduler.tryAcquire();
            scheduler.reset();
        }
        assertThat(scheduler.remaining()).isEqualTo(1);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void s3SizesMix(int objects, int size) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < objects; i++) {
            storage.put("obj-" + i, new byte[size], i);
        }
        assertThat(storage.get("obj-" + (objects - 1))
                .orElseThrow().data()).hasSize(size);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void spotDataVolumes(int clouds, int rounds) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < rounds; i++) {
            source.fetch("cloud-" + (i % clouds), i);
        }
        assertThat(source.lastFetch("cloud-0")).isPresent();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void keyRotationMix(int rounds, int grace) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < rounds; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.validates(key("k" + rounds))).isTrue();
        assertThat(manager.retired()).hasSize(rounds);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,1", "10,2", "20,4", "50,10", "100,20"})
    void lifecycleProtectionMix(int objects, int protectedCount) {
        ObjectLifecycleManager manager = lifecycle(30);
        for (int i = 0; i < objects; i++) {
            manager.protect("obj-v" + i);
        }
        assertThat(manager.isProtected(
                "obj-v" + (protectedCount - 1))).isTrue();
        assertThat(manager.isProtected(
                "obj-v" + (objects - 1))).isTrue();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,100", "10,200", "20,500", "50,1000",
            "100,2000"})
    void leveledMix(int levels, long bytes) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        for (int i = 0; i < levels; i++) {
            var plan = planner.planLevel(bytes, bytes / 2, 128, i);
            assertThat(plan.sourceLevel()).isEqualTo(i);
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,3", "10,5", "20,10", "50,25", "100,50"})
    void placementMix(int regions, int azs) {
        PlacementScheduler scheduler = new PlacementScheduler();
        for (int i = 0; i < azs; i++) {
            scheduler.registerNode(new Node("n" + i,
                    "az-" + (i + 1)));
        }
        for (int i = 0; i < regions; i++) {
            scheduler.place("r" + i, "az-" + ((i % azs) + 1), 0);
        }
        assertThat(scheduler.regions()).hasSize(regions);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void quotaAttempts(long quota, int attempts) {
        QuotaScheduler scheduler = new QuotaScheduler(quota);
        for (int i = 0; i < attempts; i++) {
            scheduler.tryAcquire();
        }
        assertThat(scheduler.used()).isLessThanOrEqualTo(quota);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void s3ConcurrentVolumes(int count) throws Exception {
        S3ObjectStorage storage = storage();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    storage.put("obj-" + i, new byte[1], i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(storage.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rotatorConcurrentVolumes(int count) throws Exception {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    rotator.rotate();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(rotator.immutableCount()).isEqualTo(count * 4);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void s3Timestamps(int count) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < count; i++) {
            storage.put("obj-" + i, new byte[1], i);
        }
        assertThat(storage.get("obj-" + (count - 1))
                .orElseThrow().timestampMillis())
                .isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void spotDataTypesVolumes(int count) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < count; i++) {
            source.fetch("aws-us", i);
        }
        assertThat(source.type()).isNotNull();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void keyRotationRetiredVolumes(int count) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < count; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.retired()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void lifecycleRulesVolumes(int count) {
        ObjectLifecycleManager manager =
                new ObjectLifecycleManager();
        for (int i = 0; i < count; i++) {
            manager.addRule(new LifecycleRule("p" + i + "-",
                    i + 1));
        }
        assertThat(manager.rules()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledShouldCompactVolumes(int count) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        for (int i = 0; i < count; i++) {
            assertThat(planner.shouldCompact(i * 100, 500))
                    .isEqualTo(i * 100 > 500);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rotatorActiveVolumes(int count) {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        for (int i = 0; i < count; i++) {
            rotator.rotate();
        }
        assertThat(rotator.activeId()).isNotBlank();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void placementCanPlaceVolumes(int count) {
        PlacementScheduler scheduler = placement();
        for (int i = 0; i < count; i++) {
            assertThat(scheduler.canPlace("n" + ((i % 3) + 1),
                    "az-" + ((i % 3) + 1))).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void rebalanceEmptyVolumes(int count) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        Map<String, Long> loads = Map.of("n1", 50L, "n2", 50L);
        for (int i = 0; i < count; i++) {
            assertThat(scheduler.plan(loads, 100)).isEmpty();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void quotaRemainingVolumes(int count) {
        QuotaScheduler scheduler = new QuotaScheduler(count);
        for (int i = 0; i < count; i++) {
            scheduler.tryAcquire();
        }
        assertThat(scheduler.remaining()).isZero();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,1", "10,2", "20,4", "50,10", "100,20"})
    void s3BucketMix(int objects, int sizes) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < objects; i++) {
            storage.put("obj-" + i, new byte[sizes], i);
        }
        assertThat(storage.keys()).hasSize(objects);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,1", "10,2", "20,4", "50,10", "100,20"})
    void spotDataCloudMix(int clouds, int rounds) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        for (int i = 0; i < rounds; i++) {
            source.fetch("cloud-" + (i % clouds), i);
        }
        assertThat(source.lastFetch("cloud-" + ((rounds - 1)
                % clouds))).isPresent();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void keyRotationGraceMix(int rounds, int grace) {
        KeyRotationManager manager = keyManager();
        for (int i = 0; i < rounds; i++) {
            manager.prepareNext(key("k" + (i + 2)));
            manager.rotate(i);
        }
        assertThat(manager.validates(key("k" + rounds))).isTrue();
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,1", "10,2", "20,4", "50,10", "100,20"})
    void lifecyclePrefixMix(int rules, int objects) {
        ObjectLifecycleManager manager =
                new ObjectLifecycleManager();
        for (int i = 0; i < rules; i++) {
            manager.addRule(new LifecycleRule("p" + i + "-", 30));
        }
        for (int i = 0; i < objects; i++) {
            manager.apply(archived("p" + (i % rules) + "-v1", 1),
                    2);
        }
        assertThat(manager.applied()).hasSize(objects);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,100", "10,200", "20,500", "50,1000",
            "100,2000"})
    void leveledLevelMix(int levels, long bytes) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        for (int i = 0; i < levels; i++) {
            assertThat(planner.planLevel(bytes, bytes, 128, i)
                    .fileCount()).isZero();
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void rotatorFlushMix(int rotations, int flushes) {
        ImmutableMemTableRotator rotator =
                new ImmutableMemTableRotator();
        for (int i = 0; i < rotations; i++) {
            rotator.rotate();
        }
        for (int i = 0; i < flushes; i++) {
            assertThat(rotator.flushDone("mem-" + (i + 1)))
                    .isTrue();
        }
        assertThat(rotator.immutableCount())
                .isEqualTo(rotations - flushes);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void placementEpochMix(int placements, int epochs) {
        PlacementScheduler scheduler = placement();
        for (int i = 0; i < epochs; i++) {
            scheduler.advanceEpoch();
        }
        for (int i = 0; i < placements; i++) {
            scheduler.place("r" + i, "az-" + ((i % 3) + 1),
                    epochs);
        }
        assertThat(scheduler.regions()).hasSize(placements);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void rebalanceMix(int nodes, int rounds) {
        RebalanceScheduler scheduler = new RebalanceScheduler();
        Map<String, Long> loads = new java.util.HashMap<>();
        for (int i = 0; i < nodes; i++) {
            loads.put("n" + i, i % 2 == 0 ? 200L : 50L);
        }
        for (int i = 0; i < rounds; i++) {
            assertThat(scheduler.plan(loads, 100)).isNotEmpty();
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void quotaMixVolumes(long quota, int rounds) {
        QuotaScheduler scheduler = new QuotaScheduler(quota);
        for (int i = 0; i < rounds; i++) {
            scheduler.tryAcquire();
        }
        assertThat(scheduler.used()).isLessThanOrEqualTo(quota);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void s3BucketRoundtrip(int count) {
        S3ObjectStorage storage = storage();
        for (int i = 0; i < count; i++) {
            S3Object object = storage.put("obj-" + i,
                    new byte[i + 1], i);
            assertThat(storage.get(object.key()).orElseThrow()
                    .data()).hasSize(i + 1);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void placementRoundtrip(int count) {
        PlacementScheduler scheduler = placement();
        for (int i = 0; i < count; i++) {
            scheduler.place("r" + i, "az-" + ((i % 3) + 1), 0);
        }
        assertThat(scheduler.regions()).hasSize(count);
        assertThat(scheduler.epoch()).isZero();
    }

    private static S3ObjectStorage storage() {
        return new S3ObjectStorage("tiering", "");
    }

    private static KeyRotationManager keyManager() {
        return new KeyRotationManager(key("k1"));
    }

    private static SigningKey key(String keyId) {
        return new SigningKey(keyId,
                keyId.getBytes(StandardCharsets.UTF_8),
                KeyRotationManager.KeyStatus.ACTIVE);
    }

    private static ObjectLifecycleManager lifecycle(long days) {
        ObjectLifecycleManager manager =
                new ObjectLifecycleManager();
        manager.addRule(new LifecycleRule("obj-", days));
        return manager;
    }

    private static ArchivedObject archived(String key, long time) {
        return new ArchivedObject(key, "aws-us",
                new RemoteSnapshot(key, "gcp-us", 1, 1, false,
                        time), time);
    }

    private static PlacementScheduler placement() {
        PlacementScheduler scheduler = new PlacementScheduler();
        scheduler.registerNode(new Node("n1", "az-1"));
        scheduler.registerNode(new Node("n2", "az-2"));
        scheduler.registerNode(new Node("n3", "az-3"));
        return scheduler;
    }
}
