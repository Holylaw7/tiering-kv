package io.tieringkv.pubsub;

import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.cluster.rpc.RpcServer;

/**
 * RPC 接收端桥（ADR-0285）：PUBSUB 帧 → 本地 broker.publish →
 * ACK 帧。
 */
public final class RpcPubSubBridge {

    private RpcPubSubBridge() {
    }

    public static void install(RpcServer server,
                               PubSubBroker broker) {
        if (server == null || broker == null) {
            throw new IllegalArgumentException(
                    "server and broker required");
        }
        server.handler(frame -> {
            if (frame.type() != RpcMessageType.PUBSUB) {
                return new RpcFrame(frame.requestId(),
                        RpcMessageType.ERROR, new byte[0]);
            }
            RpcPubSubForwarder.ChannelMessage message =
                    RpcPubSubForwarder.decode(frame.payload());
            broker.publish(message.channel(), message.payload());
            return new RpcFrame(frame.requestId(),
                    RpcMessageType.PUBSUB_RESPONSE,
                    new byte[]{1});
        });
    }
}
