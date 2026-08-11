package io.tieringkv.txn.meta;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TransactionMetadataState;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 元数据 Raft 节点（ADR-0095）：单节点 = RaftNode + 元数据状态机。 */
public final class TxnMetadataNode {

    private final RaftNode raft;
    private final TransactionMetadataState state = new TransactionMetadataState();

    public TxnMetadataNode(String id, List<RaftNode> peers) {
        this.raft = new RaftNode(id, peers,
                (index, command) -> state.apply(
                        TxnMetaCodec.decode(command).withDecisionIndex(index)),
                new LeaderElection(100, 80), 25, 10);
    }

    public RaftNode raft() {
        return raft;
    }

    public TransactionMetadataState state() {
        return state;
    }

    public CompletableFuture<Long> propose(byte[] command) {
        return raft.propose(command);
    }
}
