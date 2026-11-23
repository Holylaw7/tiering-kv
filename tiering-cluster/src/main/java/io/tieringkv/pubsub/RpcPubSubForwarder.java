package io.tieringkv.pubsub;

import io.tieringkv.cluster.rpc.RpcClient;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集群 Pub/Sub 转发（ADR-0285）：Netty RPC 转发 + 环回抑制 +
 * 失败登记；best-effort 不阻塞发布路径。
 */
public final class RpcPubSubForwarder implements PubSubForwarder {

    /** 转发消息。 */
    public record ChannelMessage(String channel, byte[] payload) {
        public ChannelMessage {
            payload = payload.clone();
        }
    }

    private final String selfNode;
    private final Map<String, InetSocketAddress> peers;
    private final RpcClient client;
    private final long timeoutMillis;
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private final AtomicLong forwarded = new AtomicLong();

    public RpcPubSubForwarder(String selfNode,
                              Map<String, InetSocketAddress> peers,
                              RpcClient client,
                              long timeoutMillis) {
        if (selfNode == null || selfNode.isBlank()
                || peers == null || client == null
                || timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "selfNode, peers, client and positive timeout "
                            + "required");
        }
        this.selfNode = selfNode;
        this.peers = Map.copyOf(peers);
        this.client = client;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public void forward(String originNode, String channel,
                        byte[] message) {
        if (originNode == null || originNode.equals(selfNode)) {
            return; // 环回抑制
        }
        RpcFrame frame = new RpcFrame(0L,
                RpcMessageType.PUBSUB, encode(channel, message));
        for (Map.Entry<String, InetSocketAddress> peer
                : peers.entrySet()) {
            if (peer.getKey().equals(originNode)) {
                continue;
            }
            client.call(peer.getValue(), frame, timeoutMillis, 0)
                    .whenComplete((response, error) -> {
                        if (error != null) {
                            failures.add(peer.getKey() + ":"
                                    + error.getMessage());
                        } else {
                            forwarded.incrementAndGet();
                        }
                    });
        }
    }

    public List<String> failures() {
        return List.copyOf(failures);
    }

    public long forwardedCount() {
        return forwarded.get();
    }

    public static byte[] encode(String channel, byte[] message) {
        byte[] channelBytes = channel.getBytes(
                StandardCharsets.UTF_8);
        byte[] payload = new byte[4 + channelBytes.length
                + message.length];
        putInt(payload, 0, channelBytes.length);
        System.arraycopy(channelBytes, 0, payload, 4,
                channelBytes.length);
        System.arraycopy(message, 0, payload,
                4 + channelBytes.length, message.length);
        return payload;
    }

    public static ChannelMessage decode(byte[] payload) {
        int channelLength = getInt(payload, 0);
        String channel = new String(payload, 4, channelLength,
                StandardCharsets.UTF_8);
        byte[] message = new byte[payload.length - 4
                - channelLength];
        System.arraycopy(payload, 4 + channelLength, message, 0,
                message.length);
        return new ChannelMessage(channel, message);
    }

    private static void putInt(byte[] bytes, int offset,
                               int value) {
        bytes[offset] = (byte) (value >>> 24);
        bytes[offset + 1] = (byte) (value >>> 16);
        bytes[offset + 2] = (byte) (value >>> 8);
        bytes[offset + 3] = (byte) value;
    }

    private static int getInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }
}
