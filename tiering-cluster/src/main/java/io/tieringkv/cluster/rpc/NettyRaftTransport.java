package io.tieringkv.cluster.rpc;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.TimeoutNowRequest;
import io.tieringkv.cluster.raft.TimeoutNowResponse;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
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
    private final RpcClient client;

    public NettyRaftTransport(String selfId, int port,
                              Map<String, InetSocketAddress> addresses) {
        this(selfId, port, addresses, RpcSecurityConfig.disabled());
    }

    public NettyRaftTransport(String selfId, int port,
                              Map<String, InetSocketAddress> addresses,
                              RpcSecurityConfig security) {
        this.selfId = selfId;
        this.addresses = Map.copyOf(addresses);
        this.server = new RpcServer(port, security);
        this.client = new RpcClient(security);
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
        return decodeExpected(call(target, rpc),
                RpcMessageType.REQUEST_VOTE_RESPONSE,
                RaftMessageCodec::decodeVoteResponse);
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> appendEntries(
            String target, AppendEntriesRequest request) {
        byte[] payload = RaftMessageCodec.encode(request);
        RpcRequest rpc = new RpcRequest(RequestId.next(),
                RpcMessageType.APPEND_ENTRIES, payload);
        return decodeExpected(call(target, rpc),
                RpcMessageType.APPEND_ENTRIES_RESPONSE,
                RaftMessageCodec::decodeAppendEntriesResponse);
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> installSnapshot(
            String target, InstallSnapshotRequest request) {
        byte[] payload = RaftMessageCodec.encode(request);
        RpcRequest rpc = new RpcRequest(RequestId.next(),
                RpcMessageType.INSTALL_SNAPSHOT, payload);
        return decodeExpected(call(target, rpc),
                RpcMessageType.INSTALL_SNAPSHOT_RESPONSE,
                RaftMessageCodec::decodeInstallSnapshotResponse);
    }

    @Override
    public CompletableFuture<TimeoutNowResponse> timeoutNow(
            String target, TimeoutNowRequest request) {
        byte[] payload = RaftMessageCodec.encode(request);
        RpcRequest rpc = new RpcRequest(RequestId.next(),
                RpcMessageType.TIMEOUT_NOW, payload);
        return decodeExpected(call(target, rpc),
                RpcMessageType.TIMEOUT_NOW_RESPONSE,
                RaftMessageCodec::decodeTimeoutNowResponse);
    }

    /**
     * 类型校验后再解码（ADR-0353 根因修复）：ERROR 帧 payload 为 UTF-8
     * 错误文本，无条件解码会把它解析成巨大 term 并污染 Raft 状态；
     * 类型不匹配一律按失败处理。
     */
    private static <T> CompletableFuture<T> decodeExpected(
            CompletableFuture<RpcResponse> call, RpcMessageType expected,
            Function<byte[], T> decoder) {
        return call.thenCompose(response -> {
            if (response.type() != expected) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "unexpected RPC response type " + response.type()
                                        + " for " + expected));
            }
            try {
                return CompletableFuture.completedFuture(
                        decoder.apply(response.payload()));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
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
            case TIMEOUT_NOW -> {
                TimeoutNowRequest request =
                        RaftMessageCodec.decodeTimeoutNowRequest(frame.payload());
                TimeoutNowResponse response = node.receiveTimeoutNow(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.TIMEOUT_NOW_RESPONSE,
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
