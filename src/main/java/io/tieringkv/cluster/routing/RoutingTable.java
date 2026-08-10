package io.tieringkv.cluster.routing;

import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.sharding.HashSlotRouter;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 权威路由表（ADR-0066）：键范围 TreeMap + slot 区间数组 + epoch 版本。
 * 线程安全（synchronized 路由；写低频读高频，可后续演进 copy-on-write）。
 */
public final class RoutingTable implements UnifiedRouter {

    private final TreeMap<byte[], RoutingTableEntry> byStartKey =
            new TreeMap<>(Arrays::compareUnsigned);
    private final RoutingTableEntry[] bySlot =
            new RoutingTableEntry[HashSlotRouter.SLOT_COUNT];
    private final Map<RegionId, RoutingTableEntry> byRegion = new HashMap<>();
    private long version;

    @Override
    public synchronized RoutingTableEntry route(byte[] key) {
        Map.Entry<byte[], RoutingTableEntry> floor = byStartKey.floorEntry(key);
        if (floor == null) {
            throw new IllegalStateException("no route for key");
        }
        RoutingTableEntry entry = floor.getValue();
        if (!entry.containsKey(key)) {
            throw new IllegalStateException("no route for key");
        }
        return entry;
    }

    @Override
    public synchronized RoutingTableEntry routeSlot(int slot) {
        RoutingTableEntry entry = bySlot[slot];
        if (entry == null) {
            throw new IllegalStateException("no route for slot " + slot);
        }
        return entry;
    }

    @Override
    public synchronized String raftGroupFor(RegionId regionId) {
        RoutingTableEntry entry = byRegion.get(regionId);
        return entry == null ? null : entry.raftGroupId();
    }

    @Override
    public synchronized long version() {
        return version;
    }

    /** 原子更新：替换键范围/槽位/region 映射并递增版本。 */
    @Override
    public synchronized void update(RoutingTableEntry entry) {
        RoutingTableEntry old = byRegion.get(entry.regionId());
        if (old != null) {
            byStartKey.remove(old.startKey());
            for (int slot = old.slotStart(); slot <= old.slotEnd(); slot++) {
                if (bySlot[slot] == old) {
                    bySlot[slot] = null;
                }
            }
        }
        byRegion.put(entry.regionId(), entry);
        byStartKey.put(entry.startKey(), entry);
        for (int slot = entry.slotStart(); slot <= entry.slotEnd(); slot++) {
            bySlot[slot] = entry;
        }
        version++;
    }

    /** 移除条目（合并/失效时使用）：键范围/槽位/region 映射清除。 */
    public synchronized void remove(RegionId regionId) {
        RoutingTableEntry old = byRegion.remove(regionId);
        if (old == null) {
            return;
        }
        byStartKey.remove(old.startKey());
        for (int slot = old.slotStart(); slot <= old.slotEnd(); slot++) {
            if (bySlot[slot] == old) {
                bySlot[slot] = null;
            }
        }
        version++;
    }

    public synchronized int entryCount() {
        return byRegion.size();
    }
}
