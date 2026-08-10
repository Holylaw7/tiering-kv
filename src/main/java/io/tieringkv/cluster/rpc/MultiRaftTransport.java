package io.tieringkv.cluster.rpc;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        return endpoint.call(target, groupId, RpcMessageType.REQUEST_VOTE,
                        RaftMessageCodec.encode(request))
                .thenApply(response -> RaftMessageCodec
                        .decodeVoteResponse(response.payload()));
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> appendEntries(
            String target, AppendEntriesRequest request) {
        return endpoint.call(target, groupId, RpcMessageType.APPEND_ENTRIES,
                        RaftMessageCodec.encode(request))
                .thenApply(response -> RaftMessageCodec
                        .decodeAppendEntriesResponse(response.payload()));
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> installSnapshot(
            String target, InstallSnapshotRequest request) {
        return endpoint.call(target, groupId, RpcMessageType.INSTALL_SNAPSHOT,
                        RaftMessageCodec.encode(request))
                .thenApply(response -> RaftMessageCodec
                        .decodeInstallSnapshotResponse(response.payload()));
    }
}
