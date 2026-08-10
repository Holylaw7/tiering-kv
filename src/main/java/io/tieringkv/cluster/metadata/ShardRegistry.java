package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardGroup;
import io.tieringkv.cluster.sharding.ShardId;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 分片注册表（ADR-0036）：分片组与 leader。 */
public final class ShardRegistry {

    private final Map<Integer, ShardGroup> shards = new ConcurrentHashMap<>();

    public void put(ShardGroup group) {
        shards.put(group.shardId().id(), group);
    }

    public ShardGroup get(int shardId) {
        return shards.get(shardId);
    }

    public void updateLeader(int shardId, String leader) {
        ShardGroup current = shards.get(shardId);
        if (current != null) {
            shards.put(shardId, new ShardGroup(current.shardId(), current.nodes(), leader));
        }
    }

    public void remove(int shardId) {
        shards.remove(shardId);
    }

    public List<ShardGroup> all() {
        return List.copyOf(shards.values());
    }

    public int size() {
        return shards.size();
    }
}
