package io.tieringkv.cluster.lifecycle.merge;

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
 * Region 合并控制器（ADR-0062）：
 * PREPARE（校验邻接/epoch/leader）→ LOCK（标记 MERGING）→
 * TRANSFER DATA（右→左零拷贝）→ UPDATE META（合并 region）→
 * TOMBSTONE（旧 region 禁止写入）。
 */
public final class MergeController {

    private final RegionManager regions;
    private final Map<RegionId, MergeTask> active = new ConcurrentHashMap<>();

    public MergeController(RegionManager regions) {
        this.regions = regions;
    }

    /** PREPARE + LOCK：校验并标记两侧 MERGING。 */
    public MergeTask beginMerge(RegionId leftId, RegionId rightId,
                                StorageEngine leftStorage,
                                StorageEngine rightStorage) {
        Region left = regions.get(leftId);
        Region right = regions.get(rightId);
        if (left == null || right == null
                || left.state() != RegionState.NORMAL
                || right.state() != RegionState.NORMAL) {
            throw new IllegalStateException("regions must be NORMAL");
        }
        if (left.leader() == null || right.leader() == null) {
            throw new IllegalStateException("regions must have leaders");
        }
        if (!Arrays.equals(left.endKey(), right.startKey())) {
            throw new IllegalArgumentException("regions are not adjacent");
        }
        if (active.containsKey(leftId) || active.containsKey(rightId)) {
            throw new IllegalStateException("region already in merge");
        }
        regions.markState(leftId, RegionState.MERGING);
        regions.markState(rightId, RegionState.MERGING);
        MergeTask task = new MergeTask(leftId, rightId, leftStorage, rightStorage);
        task.phase(MergeTask.MergePhase.LOCK);
        active.put(leftId, task);
        active.put(rightId, task);
        return task;
    }

    /** TRANSFER DATA：右 region 数据零拷贝迁移到左存储。 */
    public int transfer(MergeTask task) {
        List<RawMutation> mutations = new ArrayList<>();
        try (StorageIterator iterator = task.rightStorage().iterator()) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                long ttl = entry.expireTimestamp() >= 0
                        ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                        : -1;
                mutations.add(new RawMutation(entry.key(), entry.value(),
                        entry.version(), ttl));
            }
        }
        int moved = mutations.size();
        if (!mutations.isEmpty()) {
            task.leftStorage().applyRawBatch(mutations);
        }
        task.phase(MergeTask.MergePhase.TRANSFER);
        return moved;
    }

    /** UPDATE META：合并 region（epoch confVer+1 & version+1，路由切换）。 */
    public Region updateMeta(MergeTask task) {
        if (task.phase() != MergeTask.MergePhase.TRANSFER) {
            throw new IllegalStateException("transfer must run before meta update");
        }
        Region merged = regions.mergeRegion(task.leftId(), task.rightId());
        task.phase(MergeTask.MergePhase.UPDATE_META);
        return merged;
    }

    /** TOMBSTONE：旧 region 已由 mergeRegion 标记，任务完成。 */
    public void tombstone(MergeTask task) {
        active.remove(task.leftId());
        active.remove(task.rightId());
        task.phase(MergeTask.MergePhase.TOMBSTONE);
    }

    /** 一键合并。 */
    public Region merge(RegionId leftId, RegionId rightId,
                        StorageEngine leftStorage, StorageEngine rightStorage) {
        MergeTask task = beginMerge(leftId, rightId, leftStorage, rightStorage);
        transfer(task);
        Region merged = updateMeta(task);
        tombstone(task);
        return merged;
    }

    public RegionLifecycleState lifecycleState(RegionId regionId) {
        MergeTask task = active.get(regionId);
        if (task == null) {
            for (MergeTask candidate : active.values()) {
                if (candidate.phase() == MergeTask.MergePhase.UPDATE_META
                        && candidate.mergedId().equals(regionId)) {
                    return RegionLifecycleState.MERGE_READY;
                }
            }
            Region region = regions.get(regionId);
            return region == null || region.state() == RegionState.TOMBSTONE
                    ? RegionLifecycleState.TOMBSTONE
                    : RegionLifecycleState.NORMAL;
        }
        return switch (task.phase()) {
            case LOCK, TRANSFER -> RegionLifecycleState.MERGING;
            case UPDATE_META -> RegionLifecycleState.MERGE_READY;
            default -> RegionLifecycleState.NORMAL;
        };
    }

    public RegionManager regions() {
        return regions;
    }
}
