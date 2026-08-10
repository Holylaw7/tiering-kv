package io.tieringkv.cluster.sharding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** slot → 分片映射表（ADR-0035）：可整体分配与单 slot 重指派。 */
public final class SlotTable {

    private final int[] slotToShard = new int[HashSlotRouter.SLOT_COUNT];

    public SlotTable() {
        for (int i = 0; i < slotToShard.length; i++) {
            slotToShard[i] = -1;
        }
    }

    /** 把全部 slot 均匀分配到 shardCount 个分片。 */
    public synchronized void assignShards(int shardCount) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be positive");
        }
        for (int slot = 0; slot < slotToShard.length; slot++) {
            slotToShard[slot] = slot % shardCount;
        }
    }

    public synchronized int shardFor(int slot) {
        if (slot < 0 || slot >= slotToShard.length) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        int shard = slotToShard[slot];
        if (shard < 0) {
            throw new IllegalStateException("slot " + slot + " not assigned");
        }
        return shard;
    }

    public synchronized void reassign(int slot, int shardId) {
        if (slot < 0 || slot >= slotToShard.length) {
            throw new IllegalArgumentException("slot out of range: " + slot);
        }
        slotToShard[slot] = shardId;
    }

    public synchronized List<Integer> slotsOf(int shardId) {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < slotToShard.length; slot++) {
            if (slotToShard[slot] == shardId) {
                slots.add(slot);
            }
        }
        return slots;
    }

    public synchronized Map<Integer, Integer> snapshot() {
        Map<Integer, Integer> copy = new HashMap<>();
        for (int slot = 0; slot < slotToShard.length; slot++) {
            copy.put(slot, slotToShard[slot]);
        }
        return copy;
    }
}
