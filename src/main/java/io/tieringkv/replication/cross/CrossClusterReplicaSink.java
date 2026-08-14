package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.ReplicaSink;

import java.util.concurrent.CompletableFuture;

/**
 * 跨集群复制 ReplicaSink 适配器（ADR-0321 M3 收尾）：把本集群
 * ReplicationPipeline 的事件经 CrossClusterReplicationChannel 转发到
 * 远端集群，实现"本集群多地域 → 跨集群"串联。
 */
public final class CrossClusterReplicaSink implements ReplicaSink {

    private final String replicaId;
    private final CrossClusterReplicationChannel channel;

    public CrossClusterReplicaSink(String replicaId,
                                   CrossClusterReplicationChannel
                                           channel) {
        if (replicaId == null || channel == null) {
            throw new IllegalArgumentException(
                    "replicaId and channel required");
        }
        this.replicaId = replicaId;
        this.channel = channel;
    }

    @Override
    public CompletableFuture<Void> apply(ChangeEvent event) {
        return channel.send(event).thenApply(accepted -> {
            if (!accepted) {
                throw new IllegalStateException(
                        "replication rejected by remote");
            }
            return null;
        });
    }

    @Override
    public String replicaId() {
        return replicaId;
    }
}
