package io.tieringkv.cluster.lifecycle.split;

import io.tieringkv.cluster.lifecycle.RegionLifecycleState;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.RawMutation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Region 分裂控制器（ADR-0061）：
 * NORMAL → SPLITTING → SPLIT_READY → NORMAL；五阶段
 * PREPARE / SNAPSHOT / INSTALL / COMMIT / CLEANUP。
 * 分裂期间写入缓冲，COMMIT 按键分发到子 region。
 */
public final class SplitController {

    private final RegionManager regions;
    private final Map<RegionId, RegionSplitTask> active = new ConcurrentHashMap<>();

    public SplitController(RegionManager regions) {
        this.regions = regions;
    }

    /** PREPARE：校验 + 标记 SPLITTING。 */
    public RegionSplitTask beginSplit(RegionId regionId, byte[] splitKey,
                                      StorageEngine source,
                                      StorageEngine leftStorage,
                                      StorageEngine rightStorage) {
        Region region = regions.get(regionId);
        if (region == null || region.state() != RegionState.NORMAL) {
            throw new IllegalStateException("region not splittable: " + regionId);
        }
        if (region.endKey() == null) {
            throw new IllegalStateException("cannot split open-ended region");
        }
        int cmp = Arrays.compareUnsigned(splitKey, region.startKey());
        if (cmp <= 0 || Arrays.compareUnsigned(splitKey, region.endKey()) >= 0) {
            throw new IllegalArgumentException("split key outside region range");
        }
        regions.markState(regionId, RegionState.SPLITTING);
        RegionSplitTask task = new RegionSplitTask(
                regionId, splitKey, source, leftStorage, rightStorage);
        active.put(regionId, task);
        return task;
    }

    /** SNAPSHOT：按 splitKey 切分源数据。 */
    public SplitSnapshot snapshot(RegionSplitTask task) {
        List<KeyValueEntry> left = new ArrayList<>();
        List<KeyValueEntry> right = new ArrayList<>();
        long barrier = System.currentTimeMillis();
        try (StorageIterator iterator = task.source().iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                if (Arrays.compareUnsigned(entry.key(), task.splitKey()) < 0) {
                    left.add(entry);
                } else {
                    right.add(entry);
                }
            }
        }
        SplitSnapshot snapshot = new SplitSnapshot(left, right, barrier);
        task.snapshot(snapshot);
        task.phase(RegionSplitTask.SplitPhase.SNAPSHOT);
        return snapshot;
    }

    /** INSTALL：装载子存储 + 元数据分裂（epoch confVer+1，路由原子切换）。 */
    public List<Region> install(RegionSplitTask task) {
        SplitSnapshot snapshot = task.snapshot();
        if (snapshot == null) {
            throw new IllegalStateException("snapshot not taken");
        }
        apply(task.leftStorage(), snapshot.left());
        apply(task.rightStorage(), snapshot.right());
        List<Region> children = regions.splitRegion(
                task.regionId(), task.splitKey());
        task.phase(RegionSplitTask.SplitPhase.INSTALL);
        return children;
    }

    /** COMMIT：缓冲写入分发 + 子 region 就绪。 */
    public void commit(RegionSplitTask task) {
        for (RawMutation mutation : task.writeBuffer().drain()) {
            StorageEngine target = Arrays.compareUnsigned(
                    mutation.key(), task.splitKey()) < 0
                    ? task.leftStorage() : task.rightStorage();
            target.applyRawBatch(List.of(mutation));
        }
        task.phase(RegionSplitTask.SplitPhase.COMMIT);
    }

    /** CLEANUP：释放快照与活动任务。 */
    public void cleanup(RegionSplitTask task) {
        task.snapshot(null);
        active.remove(task.regionId());
        task.phase(RegionSplitTask.SplitPhase.CLEANUP);
    }

    /** 一键分裂（PREPARE→SNAPSHOT→INSTALL→COMMIT→CLEANUP）。 */
    public List<Region> split(RegionId regionId, byte[] splitKey,
                              StorageEngine source,
                              StorageEngine leftStorage,
                              StorageEngine rightStorage) {
        RegionSplitTask task = beginSplit(regionId, splitKey,
                source, leftStorage, rightStorage);
        snapshot(task);
        List<Region> children = install(task);
        commit(task);
        cleanup(task);
        return children;
    }

    /** 分裂期间写入（生产由网关路由到控制器）。 */
    public void bufferWrite(RegionId regionId, byte[] key, byte[] value,
                            long version, long ttlMillis) {
        RegionSplitTask task = active.get(regionId);
        if (task == null) {
            throw new IllegalStateException("region not splitting: " + regionId);
        }
        task.writeBuffer().add(key, value, version, ttlMillis);
    }

    public RegionLifecycleState lifecycleState(RegionId regionId) {
        RegionSplitTask task = active.get(regionId);
        if (task == null) {
            Region region = regions.get(regionId);
            return region == null ? RegionLifecycleState.TOMBSTONE
                    : region.state() == RegionState.TOMBSTONE
                    ? RegionLifecycleState.TOMBSTONE
                    : RegionLifecycleState.NORMAL;
        }
        return switch (task.phase()) {
            case PREPARE, SNAPSHOT -> RegionLifecycleState.SPLITTING;
            case INSTALL -> RegionLifecycleState.SPLIT_READY;
            default -> RegionLifecycleState.NORMAL;
        };
    }

    private static void apply(StorageEngine storage, List<KeyValueEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        List<RawMutation> mutations = new ArrayList<>(entries.size());
        long version = 0;
        for (KeyValueEntry entry : entries) {
            long ttl = entry.expireTimestamp() >= 0
                    ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                    : -1;
            mutations.add(new RawMutation(entry.key(), entry.value(),
                    entry.version(), ttl));
            version = entry.version();
        }
        storage.applyRawBatch(mutations);
    }
}
