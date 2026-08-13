package io.tieringkv.pubsub;

/**
 * 集群广播转发 SPI（ADR-0282）：跨节点转发预留；
 * 网络实现 Phase 53+，默认 no-op。
 */
@FunctionalInterface
public interface PubSubForwarder {

    void forward(String nodeId, String channel, byte[] message);

    static PubSubForwarder noop() {
        return (nodeId, channel, message) -> {
        };
    }
}
