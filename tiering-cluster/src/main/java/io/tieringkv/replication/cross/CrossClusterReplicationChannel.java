package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 跨集群复制通道（ADR-0321/0333）：复用 MultiRaftEndpoint RPC。
 *
 * <p>源端 {@link #send}/{@link #sendBatch} 经 callTxn(REPLICATION)
 * 发送单事件/批量帧；目标端 {@link #registerConsumer} 注册 handler，
 * 批量帧自动按序拆分为单事件消费。{@link #sendAsync} 不等待响应，
 * 成功/失败由 metrics 计数补偿。端点生命周期由调用方管理，本通道
 * close 不关闭端点。
 */
public final class CrossClusterReplicationChannel
        implements AutoCloseable {

    public static final String REPLICATION_GROUP = "replication";

    private final MultiRaftEndpoint endpoint;
    private final String remoteNode;
    private final LongAdder successCount = new LongAdder();
    private final LongAdder failureCount = new LongAdder();

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
                        if (ReplicationEventCodec.isBatch(payload)) {
                            for (ChangeEvent event :
                                    ReplicationEventCodec.decodeBatch(
                                            payload)) {
                                consumer.accept(event);
                            }
                        } else {
                            consumer.accept(
                                    ReplicationEventCodec.decode(payload));
                        }
                    } catch (IOException e) {
                        throw new IllegalArgumentException(
                                "invalid replication payload", e);
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

    /** 批量发送（ADR-0333）：一次 REPLICATION RPC 携带多事件。 */
    public CompletableFuture<Boolean> sendBatch(
            List<ChangeEvent> events) {
        try {
            return endpoint.callTxn(remoteNode, REPLICATION_GROUP,
                            RpcMessageType.REPLICATION,
                            ReplicationEventCodec.encodeBatch(events))
                    .thenApply(frame -> frame.type()
                            == RpcMessageType.REPLICATION_RESPONSE);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /** 异步 ack（ADR-0333）：不等待响应，metrics 计数补偿。 */
    public void sendAsync(ChangeEvent event) {
        send(event).whenComplete((accepted, error) -> {
            if (error == null && Boolean.TRUE.equals(accepted)) {
                successCount.increment();
            } else {
                failureCount.increment();
            }
        });
    }

    /** 异步批量 ack（ADR-0333）：批量粒度的成功/失败计数。 */
    public void sendBatchAsync(List<ChangeEvent> events) {
        sendBatch(events).whenComplete((accepted, error) -> {
            if (error == null && Boolean.TRUE.equals(accepted)) {
                successCount.increment();
            } else {
                failureCount.increment();
            }
        });
    }

    public long successCount() {
        return successCount.sum();
    }

    public long failureCount() {
        return failureCount.sum();
    }

    @Override
    public void close() {
        // 端点由调用方管理
    }
}
