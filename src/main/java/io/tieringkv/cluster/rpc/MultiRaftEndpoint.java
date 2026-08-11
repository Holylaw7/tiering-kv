package io.tieringkv.cluster.rpc;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.TimeoutNowRequest;
import io.tieringkv.cluster.raft.TimeoutNowResponse;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多 Raft 共享 RPC 端点（ADR-0058）：单端口服务多个 Raft 组；
 * 请求 payload 前缀 [groupId]，服务端按组分发，组间日志/状态完全隔离。
 * 响应保持原格式（requestId 关联），兼容既有 RPC 帧语义。
 */
public final class MultiRaftEndpoint implements AutoCloseable {

    public static final long RPC_TIMEOUT_MILLIS = 3_000;
    public static final int RPC_RETRIES = 2;

    private final String selfId;
    private final Map<String, InetSocketAddress> addresses;
    private final RpcServer server;
    private final RpcClient client;
    private final Map<String, RaftNode> localGroups = new ConcurrentHashMap<>();
    private final Map<String, TxnRpcHandler> txnHandlers = new ConcurrentHashMap<>();
    private final Map<String, ProposeHandler> proposeHandlers =
            new ConcurrentHashMap<>();

    public MultiRaftEndpoint(String selfId, int port,
                             Map<String, InetSocketAddress> addresses) {
        this(selfId, port, addresses, RpcSecurityConfig.disabled());
    }

    public MultiRaftEndpoint(String selfId, int port,
                             Map<String, InetSocketAddress> addresses,
                             RpcSecurityConfig security) {
        this.selfId = selfId;
        this.addresses = Map.copyOf(addresses);
        this.server = new RpcServer(port, security);
        this.client = new RpcClient(security);
        this.server.handler(this::handle);
        this.server.asyncHandler(this::handleAsync);
    }

    public void start() throws InterruptedException {
        server.start();
    }

    public int boundPort() {
        return server.boundPort();
    }

    public Map<String, InetSocketAddress> addresses() {
        return addresses;
    }

    public void register(String groupId, RaftNode node) {
        localGroups.put(groupId, node);
    }

    public void unregister(String groupId) {
        localGroups.remove(groupId);
    }

    /** 注册事务处理器（ADR-0083）：与 Raft 组共用单端口。 */
    public void registerTxnHandler(String groupId, TxnRpcHandler handler) {
        txnHandlers.put(groupId, handler);
    }

    public void unregisterTxnHandler(String groupId) {
        txnHandlers.remove(groupId);
    }

    /** 组提案处理器（ADR-0099）：提案经 leader 本地 propose → 复制 → 提交。 */
    public interface ProposeHandler {
        CompletableFuture<Long> propose(byte[] command);
    }

    public void registerProposeHandler(String groupId,
                                       ProposeHandler handler) {
        proposeHandlers.put(groupId, handler);
    }

    public void unregisterProposeHandler(String groupId) {
        proposeHandlers.remove(groupId);
    }

    public int groupCount() {
        return localGroups.size();
    }

    CompletableFuture<RpcFrame> call(String target, String groupId,
                                     RpcMessageType type, byte[] payload) {
        return callFrame(target, groupId, type, payload);
    }

    /** 事务 RPC 调用（ADR-0083）：供事务客户端跨节点调用 participant。 */
    public CompletableFuture<RpcFrame> callTxn(
            String target, String groupId, RpcMessageType type, byte[] payload) {
        return callFrame(target, groupId, type, payload);
    }

    /** 元数据提案 RPC（ADR-0099）：leader 返回决策索引，非 leader 重定向。 */
    public CompletableFuture<Long> callPropose(
            String target, String groupId, byte[] command) {
        return callFrame(target, groupId, RpcMessageType.META_PROPOSE,
                command).thenApply(frame -> {
            if (frame.type() == RpcMessageType.META_PROPOSE_RESPONSE) {
                return MetaRaftRpc.decodeProposeResponse(frame.payload());
            }
            throw new MetaRaftRpc.NotLeaderException(
                    new String(frame.payload(),
                            StandardCharsets.UTF_8));
        });
    }

    /** 元数据节点状态 RPC（ADR-0099）：leaderId / state / term。 */
    public CompletableFuture<MetaRaftRpc.MetaRaftStatus> callMetaStatus(
            String target, String groupId) {
        return callFrame(target, groupId, RpcMessageType.META_STATUS,
                new byte[0]).thenApply(frame ->
                MetaRaftRpc.decodeStatus(frame.payload()));
    }

    private CompletableFuture<RpcFrame> callFrame(
            String target, String groupId, RpcMessageType type, byte[] payload) {
        InetSocketAddress address = addresses.get(target);
        if (address == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("unknown peer " + target));
        }
        RpcFrame frame = new RpcFrame(RequestId.next().value(), type,
                encodeEnvelope(groupId, payload));
        return client.call(address, frame, RPC_TIMEOUT_MILLIS, RPC_RETRIES);
    }

    private RpcFrame handle(RpcFrame frame) {
        Envelope envelope = decodeEnvelope(frame.payload());
        TxnRpcHandler txnHandler = txnHandlers.get(envelope.groupId());
        if (txnHandler != null && frame.type().txn()) {
            return txnHandler.handle(frame, envelope.groupId(),
                    envelope.payload());
        }
        RaftNode node = localGroups.get(envelope.groupId());
        if (node == null) {
            throw new IllegalStateException("no raft group "
                    + envelope.groupId() + " on " + selfId);
        }
        byte[] plain = envelope.payload();
        switch (frame.type()) {
            case APPEND_ENTRIES -> {
                AppendEntriesRequest request =
                        RaftMessageCodec.decodeAppendEntriesRequest(plain);
                AppendEntriesResponse response = node.receive(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.APPEND_ENTRIES_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            case REQUEST_VOTE -> {
                VoteRequest request = RaftMessageCodec.decodeVoteRequest(plain);
                VoteResponse response = node.receive(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.REQUEST_VOTE_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            case INSTALL_SNAPSHOT -> {
                InstallSnapshotRequest request =
                        RaftMessageCodec.decodeInstallSnapshotRequest(plain);
                InstallSnapshotResponse response = node.receive(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.INSTALL_SNAPSHOT_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            case TIMEOUT_NOW -> {
                TimeoutNowRequest request =
                        RaftMessageCodec.decodeTimeoutNowRequest(plain);
                TimeoutNowResponse response = node.receiveTimeoutNow(request);
                return new RpcFrame(frame.requestId(), RpcMessageType.TIMEOUT_NOW_RESPONSE,
                        RaftMessageCodec.encode(response));
            }
            case META_STATUS -> {
                MetaRaftRpc.MetaRaftStatus status =
                        new MetaRaftRpc.MetaRaftStatus(node.leaderId(),
                                node.state().name(), node.currentTerm());
                return new RpcFrame(frame.requestId(),
                        RpcMessageType.META_STATUS_RESPONSE,
                        MetaRaftRpc.encodeStatus(status));
            }
            default -> throw new IllegalArgumentException(
                    "unexpected frame type " + frame.type());
        }
    }

    /** 异步入口（ADR-0099）：META_PROPOSE 走异步，其余委托同步处理。 */
    private CompletableFuture<RpcFrame> handleAsync(RpcFrame frame) {
        if (frame.type() != RpcMessageType.META_PROPOSE) {
            return CompletableFuture.completedFuture(handle(frame));
        }
        Envelope envelope = decodeEnvelope(frame.payload());
        ProposeHandler proposeHandler = proposeHandlers.get(
                envelope.groupId());
        if (proposeHandler == null) {
            return CompletableFuture.completedFuture(errorFrame(frame,
                    new IllegalStateException("no propose handler for group "
                            + envelope.groupId())));
        }
        return proposeHandler.propose(envelope.payload())
                .handle((index, error) -> {
                    if (error != null) {
                        return errorFrame(frame, error);
                    }
                    return new RpcFrame(frame.requestId(),
                            RpcMessageType.META_PROPOSE_RESPONSE,
                            MetaRaftRpc.encodeProposeResponse(index));
                });
    }

    private static RpcFrame errorFrame(RpcFrame request, Throwable error) {
        String message = error == null ? "meta rpc failure"
                : error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        return new RpcFrame(request.requestId(), RpcMessageType.ERROR,
                message.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encodeEnvelope(String groupId, byte[] payload) {
        byte[] id = groupId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(4 + id.length + payload.length);
        buffer.putInt(id.length);
        buffer.put(id);
        buffer.put(payload);
        return buffer.array();
    }

    private static Envelope decodeEnvelope(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int length = buffer.getInt();
        byte[] id = new byte[length];
        buffer.get(id);
        byte[] payload = new byte[buffer.remaining()];
        buffer.get(payload);
        return new Envelope(new String(id, StandardCharsets.UTF_8), payload);
    }

    private record Envelope(String groupId, byte[] payload) {
    }

    @Override
    public void close() {
        client.close();
        server.close();
    }
}
