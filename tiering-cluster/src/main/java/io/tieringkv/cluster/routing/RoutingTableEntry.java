package io.tieringkv.cluster.routing;

import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;

import java.util.Arrays;

/**
 * 统一路由条目（ADR-0066）：Region 键范围 + slot 区间 + epoch + leader +
 * raftGroup。单一权威路由表的数据单元。
 */
public record RoutingTableEntry(
        RegionId regionId,
        byte[] startKey,
        byte[] endKey,
        int slotStart,
        int slotEnd,
        RegionEpoch epoch,
        String leader,
        String raftGroupId,
        boolean migrating) {

    public RoutingTableEntry {
        startKey = startKey == null ? new byte[0] : startKey.clone();
        endKey = endKey == null ? null : endKey.clone();
        if (slotStart < 0 || slotEnd < slotStart || slotEnd > 16_383) {
            throw new IllegalArgumentException("invalid slot range");
        }
    }

    @Override
    public byte[] startKey() {
        return startKey.clone();
    }

    @Override
    public byte[] endKey() {
        return endKey == null ? null : endKey.clone();
    }

    public boolean containsKey(byte[] key) {
        if (Arrays.compareUnsigned(key, startKey) < 0) {
            return false;
        }
        return endKey == null || Arrays.compareUnsigned(key, endKey) < 0;
    }

    public boolean containsSlot(int slot) {
        return slot >= slotStart && slot <= slotEnd;
    }

    public RoutingTableEntry withLeader(String newLeader) {
        return new RoutingTableEntry(regionId, startKey, endKey,
                slotStart, slotEnd, epoch.advanceConfVer(),
                newLeader, raftGroupId, migrating);
    }

    public RoutingTableEntry withEpoch(RegionEpoch newEpoch) {
        return new RoutingTableEntry(regionId, startKey, endKey,
                slotStart, slotEnd, newEpoch, leader, raftGroupId, migrating);
    }

    public RoutingTableEntry withMigrating(boolean migrating) {
        return new RoutingTableEntry(regionId, startKey, endKey,
                slotStart, slotEnd, epoch, leader, raftGroupId, migrating);
    }
}
