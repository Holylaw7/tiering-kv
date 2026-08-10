package io.tieringkv.cluster.lifecycle;

import io.tieringkv.cluster.lifecycle.merge.MergeController;
import io.tieringkv.cluster.lifecycle.split.SplitController;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.region.Region;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.cluster.region.RegionState;
import io.tieringkv.cluster.routing.RoutingTable;
import io.tieringkv.cluster.routing.RoutingTableEntry;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/**
 * Region ↔ Raft 组迁移编排（ADR-0067）：Split/Merge 元数据与数据搬迁 +
 * 子/合并 Raft 组创建 + 路由原子切换。失败可回滚（旧 region 保持
 * NORMAL 且路由不变）。
 */
public final class RegionRaftMigrationManager {

    private final String nodeId;
    private final RaftGroupManager raftGroups;
    private final RegionManager regions;
    private final RoutingTable router;

    public RegionRaftMigrationManager(String nodeId,
                                      RaftGroupManager raftGroups,
                                      RegionManager regions,
                                      RoutingTable router) {
        this.nodeId = nodeId;
        this.raftGroups = raftGroups;
        this.regions = regions;
        this.router = router;
    }

    /**
     * Split + 子 Raft 组 + 路由切换：
     * 元数据/数据分裂 → 创建子组 → 启动 → RoutingTable 原子更新。
     */
    public List<Region> splitWithRaft(RegionId regionId, byte[] splitKey,
                                      StorageEngine source,
                                      StorageEngine leftStorage,
                                      StorageEngine rightStorage,
                                      String leftGroupId,
                                      String rightGroupId,
                                      RaftTransport leftTransport,
                                      RaftTransport rightTransport,
                                      int leftSlotStart, int leftSlotEnd,
                                      int rightSlotStart, int rightSlotEnd) {
        SplitController splitter = new SplitController(regions);
        List<Region> children;
        try {
            children = splitter.split(regionId, splitKey,
                    source, leftStorage, rightStorage);
        } catch (RuntimeException e) {
            rollbackSplit(regionId, List.of());
            throw e;
        }
        try {
            attachAndStart(leftGroupId, leftStorage, leftTransport);
            attachAndStart(rightGroupId, rightStorage, rightTransport);
        } catch (RuntimeException e) {
            rollbackSplit(regionId, children);
            throw e;
        }
        Region left = children.get(0);
        Region right = children.get(1);
        router.update(toEntry(left, leftGroupId,
                leftSlotStart, leftSlotEnd, nodeId));
        router.update(toEntry(right, rightGroupId,
                rightSlotStart, rightSlotEnd, nodeId));
        return children;
    }

    /** Merge + 目标 Raft 组 + 路由切换（旧路由移除）。 */
    public Region mergeWithRaft(RegionId leftId, RegionId rightId,
                                StorageEngine leftStorage,
                                StorageEngine rightStorage,
                                String mergedGroupId,
                                RaftTransport mergedTransport,
                                int mergedSlotStart, int mergedSlotEnd) {
        MergeController merger = new MergeController(regions);
        Region merged;
        try {
            merged = merger.merge(leftId, rightId,
                    leftStorage, rightStorage);
        } catch (RuntimeException e) {
            rollbackMerge(leftId, rightId, null);
            throw e;
        }
        try {
            attachAndStart(mergedGroupId, leftStorage, mergedTransport);
        } catch (RuntimeException e) {
            rollbackMerge(leftId, rightId, merged);
            throw e;
        }
        router.remove(leftId);
        router.remove(rightId);
        router.update(toEntry(merged, mergedGroupId,
                mergedSlotStart, mergedSlotEnd, nodeId));
        return merged;
    }

    private void attachAndStart(String groupId, StorageEngine storage,
                                RaftTransport transport) {
        if (raftGroups.groups().containsKey(groupId)) {
            return; // 已存在（恢复路径）
        }
        raftGroups.createGroup(groupId, transport, storage);
        raftGroups.raftFor(groupId).start();
    }

    private void rollbackSplit(RegionId parentId, List<Region> children) {
        // 子 region 回滚为 tombstone，父 region 恢复 NORMAL（仍处于 SPLITTING 时）
        for (Region child : children) {
            regions.markState(child.regionId(), RegionState.TOMBSTONE);
        }
        Region parent = regions.get(parentId);
        if (parent != null && parent.state() == RegionState.SPLITTING) {
            regions.markState(parentId, RegionState.NORMAL);
        }
    }

    private void rollbackMerge(RegionId leftId, RegionId rightId,
                               Region merged) {
        if (merged != null) {
            regions.markState(merged.regionId(), RegionState.TOMBSTONE);
        }
        resetIfMerging(leftId);
        resetIfMerging(rightId);
    }

    private void resetIfMerging(RegionId regionId) {
        Region region = regions.get(regionId);
        if (region != null && region.state() == RegionState.MERGING) {
            regions.markState(regionId, RegionState.NORMAL);
        }
    }

    private static RoutingTableEntry toEntry(Region region, String groupId,
                                             int slotStart, int slotEnd,
                                             String leader) {
        RegionEpoch epoch = region.epoch();
        return new RoutingTableEntry(region.regionId(), region.startKey(),
                region.endKey(), slotStart, slotEnd, epoch, leader,
                groupId, false);
    }
}
