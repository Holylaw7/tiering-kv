package io.tieringkv.pubsub;

import io.tieringkv.cluster.rpc.RpcClient;
import io.tieringkv.cluster.rpc.RpcServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 集群 Pub/Sub 转发（ADR-0285）。 */
class RpcPubSubForwarderTest {

    @Test
    void encodeDecodeRoundTrip() {
        byte[] payload = RpcPubSubForwarder.encode("news",
                "hello".getBytes(StandardCharsets.UTF_8));
        RpcPubSubForwarder.ChannelMessage message =
                RpcPubSubForwarder.decode(payload);
        assertThat(message.channel()).isEqualTo("news");
        assertThat(message.payload()).isEqualTo(
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void loopbackOriginSuppressed() {
        RpcPubSubForwarder forwarder =
                new RpcPubSubForwarder("n1", Map.of(), 
                        new RpcClient(), 500);
        forwarder.forward("n1", "c", new byte[0]);
        assertThat(forwarder.forwardedCount()).isZero();
        assertThat(forwarder.failures()).isEmpty();
    }

    @Test
    void endToEndForwardDeliversToPeerBroker() throws Exception {
        int port = freePort();
        RpcServer server = new RpcServer(port);
        PubSubBroker peerBroker = new PubSubBroker();
        RpcPubSubBridge.install(server, peerBroker);
        server.start();
        List<String> received = new CopyOnWriteArrayList<>();
        peerBroker.subscribe("news", (channel, message) ->
                received.add(new String(message,
                        StandardCharsets.UTF_8)));
        try {
            RpcClient client = new RpcClient();
            RpcPubSubForwarder forwarder =
                    new RpcPubSubForwarder("n1",
                            Map.of("n2", new InetSocketAddress(
                                    "127.0.0.1", port)),
                            client, 2000);
            forwarder.forward("n3", "news",
                    "hello".getBytes(StandardCharsets.UTF_8));
            assertThat(received).isEmpty();
            long deadline = System.currentTimeMillis() + 5000;
            while (received.isEmpty()
                    && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertThat(received).containsExactly("hello");
            client.close();
        } finally {
            server.close();
        }
    }

    @Test
    void originPeerNotForwarded() throws Exception {
        int port = freePort();
        RpcServer server = new RpcServer(port);
        PubSubBroker peerBroker = new PubSubBroker();
        RpcPubSubBridge.install(server, peerBroker);
        server.start();
        try {
            RpcClient client = new RpcClient();
            RpcPubSubForwarder forwarder =
                    new RpcPubSubForwarder("n1",
                            Map.of("n2", new InetSocketAddress(
                                    "127.0.0.1", port),
                                    "n3", new InetSocketAddress(
                                            "127.0.0.1", port)),
                            client, 2000);
            forwarder.forward("n3", "c", new byte[0]);
            TimeUnit.MILLISECONDS.sleep(300);
            assertThat(forwarder.forwardedCount())
                    .isEqualTo(1);
            client.close();
        } finally {
            server.close();
        }
    }

    @Test
    void failureRegisteredForUnreachablePeer()
            throws Exception {
        int deadPort = freePort();
        RpcClient client = new RpcClient();
        RpcPubSubForwarder forwarder =
                new RpcPubSubForwarder("n1",
                        Map.of("n2", new InetSocketAddress(
                                "127.0.0.1", deadPort)),
                        client, 300);
        forwarder.forward("n3", "c", new byte[0]);
        long deadline = System.currentTimeMillis() + 5000;
        while (forwarder.failures().isEmpty()
                && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(forwarder.failures()).isNotEmpty();
        client.close();
    }

    @ParameterizedTest(name = "payload {0}")
    @MethodSource("payloads")
    void encodeDecodeMatrix(String channel, String message) {
        byte[] payload = RpcPubSubForwarder.encode(channel,
                message.getBytes(StandardCharsets.UTF_8));
        RpcPubSubForwarder.ChannelMessage decoded =
                RpcPubSubForwarder.decode(payload);
        assertThat(decoded.channel()).isEqualTo(channel);
        assertThat(decoded.payload()).isEqualTo(
                message.getBytes(StandardCharsets.UTF_8));
    }

    static Stream<Arguments> payloads() {
        return Stream.of(
                Arguments.of("a", "b"),
                Arguments.of("news", "hello"),
                Arguments.of("user:1", "中文消息"),
                Arguments.of("", "x"),
                Arguments.of("channel", ""),
                Arguments.of("long-channel-name", "payload"));
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
