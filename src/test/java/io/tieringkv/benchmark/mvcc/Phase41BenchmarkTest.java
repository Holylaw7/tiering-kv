package io.tieringkv.benchmark.mvcc;

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

/** Phase 41 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase41BenchmarkTest {

    @ParameterizedTest(name = "puts {0}")
    @ValueSource(ints = {1000, 10000})
    void s3Throughput(int puts) {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "");
        long start = System.nanoTime();
        for (int i = 0; i < puts; i++) {
            storage.put("obj-" + i, new byte[64], i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE41-BENCH S3 %d -> %d ops/s%n",
                puts, puts * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "fetches {0}")
    @ValueSource(ints = {1000, 10000})
    void spotDataSourceThroughput(int fetches) {
        SpotMarketDataSource source = new SpotMarketDataSource(
                "", new SpotMarketFeed());
        long start = System.nanoTime();
        for (int i = 0; i < fetches; i++) {
            source.fetch("aws-us", i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE41-BENCH SPOT-DATA %d -> %d ops/s%n",
                fetches, fetches * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rotations {0}")
    @ValueSource(ints = {1000, 10000})
    void keyRotationThroughput(int rotations) {
        KeyRotationManager manager = new KeyRotationManager(
                new SigningKey("k1",
                        "k1".getBytes(StandardCharsets.UTF_8),
                        KeyRotationManager.KeyStatus.ACTIVE));
        long start = System.nanoTime();
        for (int i = 0; i < rotations; i++) {
            manager.prepareNext(new SigningKey("k" + (i + 2),
                    "k".getBytes(StandardCharsets.UTF_8),
                    KeyRotationManager.KeyStatus.ACTIVE));
            manager.rotate(i);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE41-BENCH KEY-ROT %d -> %d ops/s%n",
                rotations, rotations * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "objects {0}")
    @ValueSource(ints = {1000, 5000})
    void lifecycleThroughput(int objects) {
        ObjectLifecycleManager lifecycle =
                new ObjectLifecycleManager();
        lifecycle.addRule(new LifecycleRule("obj-", 30));
        long start = System.nanoTime();
        for (int i = 0; i < objects; i++) {
            lifecycle.apply(new ArchivedObject("obj-" + i,
                    "aws-us", new RemoteSnapshot("v" + i,
                    "gcp-us", 1, 1, false, 1), 1), 2);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE41-BENCH LIFECYCLE %d -> %d ops/s%n",
                objects, objects * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "plans {0}")
    @ValueSource(ints = {1000, 10000})
    void leveledPlanThroughput(int plans) {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        long start = System.nanoTime();
        for (int i = 0; i < plans; i++) {
            planner.planLevel(200 + (i % 100), 256, 64,
                    i % 4);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE41-BENCH LEVELED %d -> %d ops/s%n",
                plans, plans * 1_000L / elapsedMs);
    }

    @Test
    void pdSchedulingLatency() {
        PlacementScheduler placement = new PlacementScheduler();
        placement.registerNode(new Node("n1", "az-1"));
        placement.registerNode(new Node("n2", "az-2"));
        RebalanceScheduler rebalance = new RebalanceScheduler();
        QuotaScheduler quota = new QuotaScheduler(10_000);
        Map<String, Long> loads = Map.of(
                "n1", 150L, "n2", 50L);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            placement.place("r" + (i % 100), "az-1", 0);
            rebalance.plan(loads, 100);
            quota.tryAcquire();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE41-BENCH PD-SCHED %d ms%n",
                elapsedMs);
    }

    @Test
    void s3ArchiveIntegration() {
        S3ObjectStorage storage = new S3ObjectStorage("tiering",
                "");
        ObjectStorageArchive archive = new ObjectStorageArchive(
                new DataResidencyPolicy(Map.of(
                        "aws-us", "us", "gcp-us", "us")),
                "aws-us");
        ArchivedObject object = archive.upload(
                new RemoteSnapshot("v1", "gcp-us", 1, 1, false, 1),
                1);
        storage.put(object.objectKey(), new byte[8], 1);
        org.junit.jupiter.api.Assertions.assertEquals(1,
                storage.size());
    }
}
