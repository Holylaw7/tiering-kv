package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 跨集群复制通道（ADR-0321）：复用 MultiRaftEndpoint RPC。
 *
 * <p>源端 {@link #send} 经 callTxn(REPLICATION) 发送；目标端
 * {@link #registerConsumer} 注册 replication handler 解码消费。
 * 端点生命周期由调用方管理，本通道 close 不关闭端点。
 */
public final class CrossClusterReplicationChannel
        implements AutoCloseable {

    public static final String REPLICATION_GROUP = "replication";

    private final MultiRaftEndpoint endpoint;
    private final String remoteNode;

    public CrossClusterReplicationChannel(MultiRaftEndpoint endpoint,
                                          String remoteNode) {
        if (endpoint == null || remoteNode == null) {
            throw new IllegalArgumentException(
                    "endpoint and remoteNode required");
        }
        this.endpoint = endpoint;
        this.remoteNode = remoteNode;
    }

    /** 目标端：注册复制事件消费者（单消费者串行应用）。 */
    public void registerConsumer(Consumer<ChangeEvent> consumer) {
        endpoint.registerTxnHandler(REPLICATION_GROUP,
                (RpcFrame request, String groupId, byte[] payload) -> {
                    try {
                        consumer.accept(
                                ReplicationEventCodec.decode(payload));
                    } catch (IOException e) {
                        throw new IllegalArgumentException(
                                "invalid replication event", e);
                    }
                    return new RpcFrame(request.requestId(),
                            RpcMessageType.REPLICATION_RESPONSE,
                            new byte[0]);
                });
    }

    /** 源端：发送事件到远端集群。 */
    public CompletableFuture<Boolean> send(ChangeEvent event) {
        try {
            return endpoint.callTxn(remoteNode, REPLICATION_GROUP,
                            RpcMessageType.REPLICATION,
                            ReplicationEventCodec.encode(event))
                    .thenApply(frame -> frame.type()
                            == RpcMessageType.REPLICATION_RESPONSE);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void close() {
        // 端点由调用方管理
    }
}
