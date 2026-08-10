package io.tieringkv.cluster.raft;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 进程内传输（ADR-0041 回退路径）：保持 Phase 11 的对象直调语义，
 * 供单元测试与本地原型使用。
 */
public final class LocalRaftTransport implements RaftTransport {

    private final List<RaftNode> peers;
    private final String selfId;

    public LocalRaftTransport(List<RaftNode> peers, String selfId) {
        this.peers = peers;
        this.selfId = selfId;
    }

    @Override
    public List<String> peerIds() {
        return peers.stream().map(RaftNode::id).toList();
    }

    @Override
    public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
        return CompletableFuture.completedFuture(find(target).receive(request));
    }

    @Override
    public CompletableFuture<AppendEntriesResponse> appendEntries(
            String target, AppendEntriesRequest request) {
        return CompletableFuture.completedFuture(find(target).receive(request));
    }

    @Override
    public CompletableFuture<InstallSnapshotResponse> installSnapshot(
            String target, InstallSnapshotRequest request) {
        return CompletableFuture.completedFuture(find(target).receive(request));
    }

    @Override
    public CompletableFuture<TimeoutNowResponse> timeoutNow(
            String target, TimeoutNowRequest request) {
        return CompletableFuture.completedFuture(find(target).receiveTimeoutNow(request));
    }

    private RaftNode find(String id) {
        for (RaftNode peer : peers) {
            if (peer.id().equals(id)) {
                return peer;
            }
        }
        throw new IllegalStateException("no local peer " + id);
    }
}
