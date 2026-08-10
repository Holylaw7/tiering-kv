package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardGroup;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 元数据状态机（ADR-0047）：节点注册 / 分片拓扑 / slot 归属 / 迁移状态；
 * 由 Raft 日志按序 apply，跨副本一致。
 */
public final class MetadataState {

    private final NodeRegistry nodeRegistry = new NodeRegistry();
    private final TopologyManager topologyManager = new TopologyManager();
    private final Map<Integer, String> migrationStatus = new ConcurrentHashMap<>();
    private final AtomicInteger applyCount = new AtomicInteger();
    private volatile String lastAppliedCommand;

    public synchronized void apply(byte[] command) {
        MetadataCodec.Decoded decoded = MetadataCodec.decode(command);
        applyCount.incrementAndGet();
        lastAppliedCommand = decoded.type().name();
        switch (decoded.type()) {
            case JOIN -> nodeRegistry.register(decoded.nodeId());
            case LEAVE -> nodeRegistry.unregister(decoded.nodeId());
            case CREATE_SHARD -> topologyManager.shardRegistry().put(
                    new ShardGroup(decoded.shardId(), decoded.nodes(), decoded.leader()));
            case UPDATE_LEADER -> topologyManager.shardRegistry()
                    .updateLeader(decoded.shardIdValue(), decoded.leader());
            case ASSIGN_SLOTS -> topologyManager.slotTable()
                    .assignShards(decoded.shardCount());
            case MIGRATION_STATUS -> migrationStatus.put(
                    decoded.shardIdValue(), decoded.migrationStatus());
            default -> throw new IllegalArgumentException(
                    "unknown metadata command " + decoded.type());
        }
    }

    public NodeRegistry nodes() {
        return nodeRegistry;
    }

    public TopologyManager topology() {
        return topologyManager;
    }

    public Map<Integer, String> migrationStatus() {
        return Map.copyOf(migrationStatus);
    }

    public String migrationStatus(int shardId) {
        return migrationStatus.getOrDefault(shardId, "UNKNOWN");
    }

    public int applyCount() {
        return applyCount.get();
    }

    public String lastAppliedCommand() {
        return lastAppliedCommand;
    }
}
