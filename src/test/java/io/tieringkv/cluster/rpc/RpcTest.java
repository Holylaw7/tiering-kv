package io.tieringkv.cluster.rpc;

import io.netty.channel.embedded.EmbeddedChannel;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Netty RPC（ADR-0041）：编解码/关联/超时/重试/重连/传输。 */
class RpcTest {

    private final List<AutoCloseable> resources = new ArrayList<>();

    @BeforeEach
    void setUp() {
    }

    @AfterEach
    void tearDown() {
        for (int i = resources.size() - 1; i >= 0; i--) {
            try {
                resources.get(i).close();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    @Test
    void frameCodecRoundTrip() {
        EmbeddedChannel encoder = new EmbeddedChannel(new RpcCodec.Encoder());
        EmbeddedChannel decoder = new EmbeddedChannel(new RpcCodec.Decoder());
        RpcFrame frame = new RpcFrame(42, RpcMessageType.REQUEST_VOTE, bytes("vote"));
        encoder.writeOutbound(frame);
        io.netty.buffer.ByteBuf encoded = encoder.readOutbound();
        decoder.writeInbound(encoded);
        RpcFrame decoded = decoder.readInbound();
        assertThat(decoded.requestId()).isEqualTo(42);
        assertThat(decoded.type()).isEqualTo(RpcMessageType.REQUEST_VOTE);
        assertThat(decoded.payload()).isEqualTo(bytes("vote"));
        encoder.finish();
        decoder.finish();
    }

    @Test
    void requestResponseOverTcp() throws Exception {
        RpcServer server = server(echo());
        RpcClient client = client();
        RpcFrame response = client.call(serverAddress(server),
                new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE,
                        bytes("hello")), 3000, 0).get(3, TimeUnit.SECONDS);
        assertThat(response.payload()).isEqualTo(bytes("hello"));
    }

    @Test
    void concurrentCallsCorrelated() throws Exception {
        RpcServer server = server(echo());
        RpcClient client = client();
        int calls = 50;
        CountDownLatch latch = new CountDownLatch(calls);
        List<CompletableFuture<RpcFrame>> futures = new ArrayList<>();
        for (int i = 0; i < calls; i++) {
            byte[] payload = bytes("payload-" + i);
            CompletableFuture<RpcFrame> future = client.call(serverAddress(server),
                    new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE,
                            payload), 3000, 0);
            futures.add(future);
            future.whenComplete((r, e) -> latch.countDown());
        }
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < calls; i++) {
            RpcFrame response = futures.get(i).get(1, TimeUnit.SECONDS);
            assertThat(response.payload()).isEqualTo(bytes("payload-" + i));
        }
    }

    @Test
    void timeoutCompletesExceptionally() throws Exception {
        RpcServer server = server(frame -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return frame;
        });
        RpcClient client = client();
        CompletableFuture<RpcFrame> future = client.call(serverAddress(server),
                new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE,
                        bytes("slow")), 100, 0);
        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void connectionReuse() throws Exception {
        RpcServer server = server(echo());
        RpcClient client = client();
        InetSocketAddress address = serverAddress(server);
        for (int i = 0; i < 10; i++) {
            client.call(address, new RpcFrame(RequestId.next().value(),
                    RpcMessageType.REQUEST_VOTE, bytes("x")), 3000, 0).get(3, TimeUnit.SECONDS);
        }
        assertThat(client.connectionCount()).isEqualTo(1);
    }

    @Test
    void reconnectAfterServerRestart() throws Exception {
        int port = freePort();
        RpcServer first = server(port, echo());
        RpcClient client = client();
        client.call(new InetSocketAddress("127.0.0.1", port),
                new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE,
                        bytes("before")), 3000, 0).get(3, TimeUnit.SECONDS);
        first.close();
        RpcServer second = server(port, echo());
        resources.add(second);
        RpcFrame response = client.call(new InetSocketAddress("127.0.0.1", port),
                new RpcFrame(RequestId.next().value(), RpcMessageType.REQUEST_VOTE,
                        bytes("after")), 3000, 2).get(3, TimeUnit.SECONDS);
        assertThat(response.payload()).isEqualTo(bytes("after"));
    }

    @Test
    void retryAfterTransientFailure() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        RpcServer server = server(frame -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("transient failure");
            }
            return frame;
        });
        RpcClient client = client();
        RpcFrame response = client.call(serverAddress(server),
                new RpcFrame(RequestId.next().value(), RpcMessageType.APPEND_ENTRIES,
                        bytes("retry")), 500, 2).get(5, TimeUnit.SECONDS);
        assertThat(response.payload()).isEqualTo(bytes("retry"));
        assertThat(attempts.get()).isGreaterThan(1);
    }

    @Test
    void voteRequestCodecRoundTrip() {
        VoteRequest request = new VoteRequest(7, "n2", 42, 5);
        assertThat(RaftMessageCodec.decodeVoteRequest(
                RaftMessageCodec.encode(request))).isEqualTo(request);
    }

    @Test
    void voteResponseCodecRoundTrip() {
        VoteResponse response = new VoteResponse(7, true);
        assertThat(RaftMessageCodec.decodeVoteResponse(
                RaftMessageCodec.encode(response))).isEqualTo(response);
    }

    @Test
    void appendEntriesCodecRoundTrip() {
        AppendEntriesRequest request = new AppendEntriesRequest(
                3, "n1", 4, 2, List.of(
                new LogEntry(3, 5, bytes("cmd1")),
                new LogEntry(3, 6, bytes("cmd2"))), 6);
        assertThat(RaftMessageCodec.decodeAppendEntriesRequest(
                RaftMessageCodec.encode(request))).isEqualTo(request);
    }

    @Test
    void appendEntriesResponseCodecRoundTrip() {
        AppendEntriesResponse response = new AppendEntriesResponse(3, true, 6);
        assertThat(RaftMessageCodec.decodeAppendEntriesResponse(
                RaftMessageCodec.encode(response))).isEqualTo(response);
    }

    @Test
    void installSnapshotCodecRoundTrip() {
        InstallSnapshotRequest request = new InstallSnapshotRequest(
                4, "n1", 90000, 3, bytes("snapshot-state"));
        assertThat(RaftMessageCodec.decodeInstallSnapshotRequest(
                RaftMessageCodec.encode(request))).isEqualTo(request);
    }

    @Test
    void installSnapshotResponseCodecRoundTrip() {
        InstallSnapshotResponse response = new InstallSnapshotResponse(4, true);
        assertThat(RaftMessageCodec.decodeInstallSnapshotResponse(
                RaftMessageCodec.encode(response))).isEqualTo(response);
    }

    @Test
    void nettyTransportAppendEntriesEndToEnd() throws Exception {
        Fixture fixture = nettyPair();
        AppendEntriesRequest request = new AppendEntriesRequest(
                1, "a", -1, 0, List.of(new LogEntry(1, 0, bytes("x"))), 0);
        AppendEntriesResponse response = fixture.transportA()
                .appendEntries("b", request).get(3, TimeUnit.SECONDS);
        assertThat(response.success()).isTrue();
        assertThat(response.matchIndex()).isZero();
    }

    @Test
    void nettyTransportRequestVoteEndToEnd() throws Exception {
        Fixture fixture = nettyPair();
        VoteRequest request = new VoteRequest(2, "a", -1, 0);
        VoteResponse response = fixture.transportA()
                .requestVote("b", request).get(3, TimeUnit.SECONDS);
        assertThat(response.granted()).isTrue();
        assertThat(response.term()).isEqualTo(2);
    }

    @Test
    void nettyTransportInstallSnapshotEndToEnd() throws Exception {
        Fixture fixture = nettyPair();
        InstallSnapshotRequest request = new InstallSnapshotRequest(
                3, "a", 100, 2, bytes("state"));
        InstallSnapshotResponse response = fixture.transportA()
                .installSnapshot("b", request).get(3, TimeUnit.SECONDS);
        assertThat(response.success()).isTrue();
    }

    @Test
    void unknownPeerFails() {
        NettyRaftTransport transport = new NettyRaftTransport("a", 0,
                Map.of("b", new InetSocketAddress("127.0.0.1", 1)));
        assertThatThrownBy(() -> transport.appendEntries("c",
                new AppendEntriesRequest(1, "a", -1, 0, List.of(), 0))
                .get(1, TimeUnit.SECONDS))
                .isInstanceOf(java.util.concurrent.ExecutionException.class);
    }

    @Test
    void largePayloadRoundTrip() throws Exception {
        RpcServer server = server(echo());
        RpcClient client = client();
        byte[] payload = new byte[1024 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        RpcFrame response = client.call(serverAddress(server),
                new RpcFrame(RequestId.next().value(), RpcMessageType.INSTALL_SNAPSHOT,
                        payload), 5000, 0).get(5, TimeUnit.SECONDS);
        assertThat(response.payload()).isEqualTo(payload);
    }

    @Test
    void raftNodeRestoresTermAndCommitFromPersistentState() throws Exception {
        // RaftNode 生产构造：持久日志 + 持久状态 + 快照均接入（恢复语义冒烟）
        var dir = java.nio.file.Files.createTempDirectory("raft-node-rpc");
        resources.add(() -> {
        });
        var state = io.tieringkv.cluster.raft.log.RaftPersistentState.open(dir);
        state.persist(5, "old-leader", 3);
        List<String> applied = new ArrayList<>();
        RaftNode node = new RaftNode("n1",
                new io.tieringkv.cluster.raft.LocalRaftTransport(List.of(), "n1"),
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                new LeaderElection(100, 80), 25, 10,
                io.tieringkv.cluster.raft.log.FileRaftLog.open(dir, io.tieringkv.cluster.raft.log.Durability.NONE),
                state, null);
        resources.add(node);
        assertThat(node.currentTerm()).isEqualTo(5);
        node.close();
    }

    private Fixture nettyPair() throws Exception {
        int portA = freePort();
        int portB = freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "a", new InetSocketAddress("127.0.0.1", portA),
                "b", new InetSocketAddress("127.0.0.1", portB));
        NettyRaftTransport transportA = new NettyRaftTransport("a", portA, addresses);
        NettyRaftTransport transportB = new NettyRaftTransport("b", portB, addresses);
        List<String> applied = new ArrayList<>();
        var dirA = java.nio.file.Files.createTempDirectory("raft-rpc-a");
        var dirB = java.nio.file.Files.createTempDirectory("raft-rpc-b");
        var snapshotA = io.tieringkv.cluster.raft.snapshot.SnapshotManager.open(
                dirA, () -> bytes("state-a"), ignored -> {
                });
        var snapshotB = io.tieringkv.cluster.raft.snapshot.SnapshotManager.open(
                dirB, () -> bytes("state-b"), ignored -> {
                });
        RaftNode nodeA = new RaftNode("a", transportA,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                new LeaderElection(100, 80), 25, 10,
                new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, snapshotA);
        RaftNode nodeB = new RaftNode("b", transportB,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                new LeaderElection(100, 80), 25, 10,
                new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, snapshotB);
        transportA.register("a", nodeA);
        transportB.register("b", nodeB);
        transportA.start();
        transportB.start();
        resources.add(transportA);
        resources.add(transportB);
        return new Fixture(transportA, transportB);
    }

    private RpcServer server(java.util.function.Function<RpcFrame, RpcFrame> handler)
            throws Exception {
        return server(freePort(), handler);
    }

    private RpcServer server(int port, java.util.function.Function<RpcFrame, RpcFrame> handler)
            throws InterruptedException {
        RpcServer server = new RpcServer(port);
        server.handler(handler);
        server.start();
        resources.add(server);
        return server;
    }

    private RpcClient client() {
        RpcClient client = new RpcClient();
        resources.add(client);
        return client;
    }

    private static java.util.function.Function<RpcFrame, RpcFrame> echo() {
        return frame -> new RpcFrame(frame.requestId(),
                RpcMessageType.REQUEST_VOTE_RESPONSE, frame.payload());
    }

    private static InetSocketAddress serverAddress(RpcServer server) {
        return new InetSocketAddress("127.0.0.1", server.boundPort());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(NettyRaftTransport transportA, NettyRaftTransport transportB) {
    }
}
