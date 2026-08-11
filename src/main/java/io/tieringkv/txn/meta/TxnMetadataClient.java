package io.tieringkv.txn.meta;

import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.rpc.MetaRaftRpc;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** 协调器侧元数据客户端（ADR-0095）：命令提交到当前 leader。 */
public final class TxnMetadataClient {

    private final List<TxnMetadataNode> nodes;
    private final MultiRaftEndpoint endpoint;
    private final String groupId;
    private final List<String> nodeIds;

    public TxnMetadataClient(List<TxnMetadataNode> nodes) {
        this.nodes = List.copyOf(nodes);
        this.endpoint = null;
        this.groupId = null;
        this.nodeIds = List.of();
    }

    /** 网络模式（ADR-0099）：经共享端点轮询 leader 提案。 */
    public TxnMetadataClient(MultiRaftEndpoint endpoint, String groupId,
                             List<String> nodeIds) {
        this.nodes = List.of();
        this.endpoint = endpoint;
        this.groupId = groupId;
        this.nodeIds = List.copyOf(nodeIds);
    }

    public Function<byte[], CompletableFuture<Long>> proposer() {
        if (endpoint != null) {
            return command -> proposeNetwork(command, 0, 0);
        }
        return command -> leader().propose(command);
    }

    public TxnMetadataNode leader() {
        if (endpoint != null) {
            throw new IllegalStateException(
                    "network client has no local leader node");
        }
        for (TxnMetadataNode node : nodes) {
            RaftNode raft = node.raft();
            if (raft.state() == RaftState.LEADER
                    && raft.id().equals(raft.leaderId())) {
                return node;
            }
        }
        throw new IllegalStateException("no metadata leader");
    }

    public List<TxnMetadataNode> nodes() {
        return nodes;
    }

    /** 网络模式 leader 探测（ADR-0099）：轮询各节点状态。 */
    public String leaderId() {
        if (endpoint == null) {
            return leader().raft().id();
        }
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            for (String nodeId : nodeIds) {
                try {
                    MetaRaftRpc.MetaRaftStatus status = endpoint
                            .callMetaStatus(nodeId, groupId)
                            .get(2, TimeUnit.SECONDS);
                    if (nodeId.equals(status.leaderId())) {
                        return nodeId;
                    }
                } catch (Exception ignored) {
                    // 节点不可用/选举中
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        }
        throw new IllegalStateException("no metadata leader");
    }

    private CompletableFuture<Long> proposeNetwork(byte[] command,
                                                   int nodeIndex,
                                                   int round) {
        if (nodeIndex >= nodeIds.size()) {
            if (round < 5) {
                return CompletableFuture.supplyAsync(() -> null,
                        CompletableFuture.delayedExecutor(100,
                                TimeUnit.MILLISECONDS))
                        .thenCompose(ignored ->
                                proposeNetwork(command, 0, round + 1));
            }
            return CompletableFuture.failedFuture(
                    new IllegalStateException("no metadata leader"));
        }
        String target = nodeIds.get(nodeIndex);
        return endpoint.callPropose(target, groupId, command)
                .handle((index, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(index);
                    }
                    if (error instanceof MetaRaftRpc.NotLeaderException) {
                        return proposeNetwork(command, nodeIndex + 1, round);
                    }
                    // 连接/超时故障：尝试下一节点，下一轮再回绕
                    return proposeNetwork(command, nodeIndex + 1, round);
                }).thenCompose(future -> future);
    }
}
