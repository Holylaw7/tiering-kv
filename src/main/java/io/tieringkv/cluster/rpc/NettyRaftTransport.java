package io.tieringkv.cluster.rpc;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Netty TCP 传输（ADR-0041）：本地节点注册表 + 按目标地址的 RPC 调用；
 * 连接复用、超时（默认 3s）、幂等重试（2 次）。
 */
public final class NettyRaftTransport implements RaftTransport, AutoCloseable {

    public static final long RPC_TIMEOUT_MILLIS = 3_000;
    public static final int RPC_RETRIES = 2;

    private final String selfId;
    private final Map<String, InetSocketAddress> addresses;
    private volatile RaftNode localNode;
    private final RpcServer server;
    private final RpcClient client = new RpcClient();

    public NettyRaftTransport(String selfId, int port,
                              Map<String, InetSocketAddress> addresses) {
        this.selfId = selfId;
        this.addresses = Map.copyOf(addresses);
        this.server = new RpcServer(port);
        this.server.handler(this::handle);
    }

    public void register(String id, RaftNode node) {
        if (!id.equals(selfId)) {
            throw new IllegalArgumentException(
                    "node id " + id + " does not match transport self " + selfId);
        }
        this.localNode = node;
    }

    public void start() throws InterruptedException {
        server.start();
    }

    public int boundPort() {
        return server.boundPort();
    }

    @Override
    public List<String> peerIds() {
        return List.copyOf(addresses.keySet());
    }

    @Override
    public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
        byte[] payload = RaftMessageCodec.encode(request);
        RpcRequest rpc = new RpcRequest(RequestId.next(),
                RpcMessageType.REQUEST_VOTE, payload);
        return call(target, rpc).thenApply(response ->
                RaftMessageCodec.decodeVoteResponse(response.payload()));
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> appendEntries(
            String target, AppendEntriesRequest request) {
        byte[] payload = RaftMessageCodec.encode(request);
        RpcRequest rpc = new RpcRequest(RequestId.next(),
                RpcMessageType.APPEND_ENTRIES, payload);
        return call(target, rpc).thenApply(response ->
                RaftMessageCodec.decodeAppendEntriesResponse(response.payload()));
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> installSnapshot(
            String target, InstallSnapshotRequest request) {
        byte[] payload = RaftMessageCodec.encode(request);
        RpcRequest rpc = new RpcRequest(RequestId.next(),
                RpcMessageType.INSTALL_SNAPSHOT, payload);
        return call(target, rpc).thenApply(response ->
                RaftMessageCodec.decodeInstallSnapshotResponse(response.payload()));
    }

    private CompletableFuture<RpcResponse> call(String target, RpcRequest request) {
        InetSocketAddress address = addresses.get(target);
        if (address == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown peer " + target));
        }
        return client.call(address, request.toFrame(), RPC_TIMEOUT_MILLIS, RPC_RETRIES)
                .thenApply(RpcResponse::fromFrame);
    }

    private RpcFrame handle(RpcFrame frame) {
        RaftNode node = localNode;
        if (node == null) {
            throw new IllegalStateException("no local raft node registered on " + selfId);
        }
        switch (frame.type()) {
            case APPEND_ENTRIES -> {
                AppendEntriesRequest request =
                        RaftMessageCodec.decodeAppendEntriesRequest(frame.payload());
                AppendEntriesResponse response = node.receive(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.APPEND_ENTRIES_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            case REQUEST_VOTE -> {
                VoteRequest request = RaftMessageCodec.decodeVoteRequest(frame.payload());
                VoteResponse response = node.receive(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.REQUEST_VOTE_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            case INSTALL_SNAPSHOT -> {
                InstallSnapshotRequest request =
                        RaftMessageCodec.decodeInstallSnapshotRequest(frame.payload());
                InstallSnapshotResponse response = node.receive(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.INSTALL_SNAPSHOT_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            default -> throw new IllegalArgumentException("unexpected frame type " + frame.type());
        }
    }

    @Override
    public void close() {
        client.close();
        server.close();
    }
}
