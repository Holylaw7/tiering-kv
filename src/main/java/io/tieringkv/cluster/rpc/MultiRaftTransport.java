package io.tieringkv.cluster.rpc;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.TimeoutNowRequest;
import io.tieringkv.cluster.raft.TimeoutNowResponse;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 多 Raft 组传输包装（ADR-0058）：每个组一个实例，共享同一端点
 * （单端口 + 连接池），RaftTransport 接口不变。
 */
public final class MultiRaftTransport implements RaftTransport {

    private final String groupId;
    private final MultiRaftEndpoint endpoint;

    public MultiRaftTransport(String groupId, MultiRaftEndpoint endpoint) {
        this.groupId = groupId;
        this.endpoint = endpoint;
    }

    @Override
    public List<String> peerIds() {
        return List.copyOf(endpoint.addresses().keySet());
    }

    @Override
    public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
        return decodeExpected(
                endpoint.call(target, groupId, RpcMessageType.REQUEST_VOTE,
                        RaftMessageCodec.encode(request)),
                RpcMessageType.REQUEST_VOTE_RESPONSE,
                RaftMessageCodec::decodeVoteResponse);
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> appendEntries(
            String target, AppendEntriesRequest request) {
        return decodeExpected(
                endpoint.call(target, groupId, RpcMessageType.APPEND_ENTRIES,
                        RaftMessageCodec.encode(request)),
                RpcMessageType.APPEND_ENTRIES_RESPONSE,
                RaftMessageCodec::decodeAppendEntriesResponse);
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> installSnapshot(
            String target, InstallSnapshotRequest request) {
        return decodeExpected(
                endpoint.call(target, groupId, RpcMessageType.INSTALL_SNAPSHOT,
                        RaftMessageCodec.encode(request)),
                RpcMessageType.INSTALL_SNAPSHOT_RESPONSE,
                RaftMessageCodec::decodeInstallSnapshotResponse);
    }

    @Override
    public CompletableFuture<TimeoutNowResponse> timeoutNow(
            String target, TimeoutNowRequest request) {
        return decodeExpected(
                endpoint.call(target, groupId, RpcMessageType.TIMEOUT_NOW,
                        RaftMessageCodec.encode(request)),
                RpcMessageType.TIMEOUT_NOW_RESPONSE,
                RaftMessageCodec::decodeTimeoutNowResponse);
    }

    /**
     * 类型校验后再解码（ADR-0353 根因修复）：对端返回 ERROR 帧（如组已
     * 注销）时，payload 是 UTF-8 错误文本；若无条件按 RESPONSE 解码，
     * 前 8 字节会被解析成巨大 term 并污染调用方 Raft 状态。类型不匹配
     * 一律按失败处理，禁止信任错误帧中的 term。
     */
    private static <T> CompletableFuture<T> decodeExpected(
            CompletableFuture<RpcFrame> call, RpcMessageType expected,
            Function<byte[], T> decoder) {
        return call.thenCompose(frame -> {
            if (frame.type() != expected) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "unexpected RPC response type " + frame.type()
                                        + " for " + expected));
            }
            try {
                return CompletableFuture.completedFuture(
                        decoder.apply(frame.payload()));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }
}
