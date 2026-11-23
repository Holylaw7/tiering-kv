package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.ShardGroup;
import io.tieringkv.cluster.sharding.SlotTable;

/** 拓扑管理（ADR-0036）：slot 表 + 分片组联合查询。 */
public final class TopologyManager {

    private final SlotTable slotTable = new SlotTable();
    private final ShardRegistry shardRegistry = new ShardRegistry();

    public SlotTable slotTable() {
        return slotTable;
    }

    public ShardRegistry shardRegistry() {
        return shardRegistry;
    }

    public String leaderFor(byte[] key) {
        int slot = HashSlotRouter.slot(key);
        int shardId = slotTable.shardFor(slot);
        ShardGroup group = shardRegistry.get(shardId);
        if (group == null || group.leader() == null) {
            throw new IllegalStateException("no leader for shard " + shardId);
        }
        return group.leader();
    }

    public int shardFor(byte[] key) {
        return slotTable.shardFor(HashSlotRouter.slot(key));
    }
}
