package io.tieringkv.cluster.metadata;

import io.tieringkv.cluster.sharding.ShardId;

import java.util.List;

/** 元数据客户端（ADR-0047）：经 MetadataRaftGroup 读写元数据。 */
public final class MetadataClient {

    private final MetadataRaftGroup group;

    public MetadataClient(MetadataRaftGroup group) {
        this.group = group;
    }

    public void join(String nodeId) {
        group.write(MetadataCodec.join(nodeId));
    }

    public void leave(String nodeId) {
        group.write(MetadataCodec.leave(nodeId));
    }

    public void createShard(ShardId shardId, List<String> nodes, String leader) {
        group.write(MetadataCodec.createShard(shardId, nodes, leader));
    }

    public void updateLeader(int shardId, String leader) {
        group.write(MetadataCodec.updateLeader(shardId, leader));
    }

    public void assignSlots(int shardCount) {
        group.write(MetadataCodec.assignSlots(shardCount));
    }

    public void migrationStatus(int shardId, String status) {
        group.write(MetadataCodec.migrationStatus(shardId, status));
    }

    public MetadataState state() {
        MetadataState state = group.leaderState();
        if (state == null) {
            throw new IllegalStateException("no metadata leader");
        }
        return state;
    }

    public MetadataRaftGroup group() {
        return group;
    }
}
