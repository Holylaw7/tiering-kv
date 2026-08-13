package io.tieringkv.pubsub;

/** 消息订阅者（ADR-0282）：channel + 消息字节。 */
@FunctionalInterface
public interface Subscriber {
    void onMessage(String channel, byte[] message);
}
