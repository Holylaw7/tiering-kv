package io.tieringkv.cluster.raft;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Raft 消息传输抽象（ADR-0041）：本地直调（测试）与 Netty TCP（生产）可替换。
 */
public interface RaftTransport {

    /** 集群节点 ID 列表（含自身；RaftNode 内部跳过自身）。 */
    List<String> peerIds();

    CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request);

    CompletableFuture<AppendEntriesResponse> appendEntries(
            String target, AppendEntriesRequest request);

    CompletableFuture<InstallSnapshotResponse> installSnapshot(
            String target, InstallSnapshotRequest request);
}
