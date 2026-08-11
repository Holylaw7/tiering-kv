package io.tieringkv.txn.meta;

import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** 协调器侧元数据客户端（ADR-0095）：命令提交到当前 leader。 */
public final class TxnMetadataClient {

    private final List<TxnMetadataNode> nodes;

    public TxnMetadataClient(List<TxnMetadataNode> nodes) {
        this.nodes = List.copyOf(nodes);
    }

    public Function<byte[], CompletableFuture<Long>> proposer() {
        return command -> leader().propose(command);
    }

    public TxnMetadataNode leader() {
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
}
