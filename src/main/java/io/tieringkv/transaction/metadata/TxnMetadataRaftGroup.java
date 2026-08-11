package io.tieringkv.transaction.metadata;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * 元数据 Raft 组（ADR-0084）：复用 RaftNode + 内存日志，不修改共识语义；
 * 提案自动路由到当前 leader，leader 变更后重新解析。
 */
public final class TxnMetadataRaftGroup implements AutoCloseable {

    private final List<RaftNode> nodes;

    private TxnMetadataRaftGroup(List<RaftNode> nodes) {
        this.nodes = nodes;
    }

    public static TxnMetadataRaftGroup start(int count)
            throws InterruptedException {
        List<RaftNode> nodes = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "meta-" + i;
            RaftNode node = new RaftNode(id, peers,
                    (index, command) -> {
                    }, new LeaderElection(100, 80), 25, 10);
            nodes.add(node);
        }
        peers.addAll(nodes);
        for (RaftNode node : nodes) {
            node.start();
        }
        awaitLeader(nodes, 5_000);
        return new TxnMetadataRaftGroup(nodes);
    }

    /** 提案函数：自动解析当前 leader（leader 变更重试由调用方负责）。 */
    public Function<byte[], CompletableFuture<Long>> proposer() {
        return command -> leader().propose(command);
    }

    public RaftNode leader() {
        for (RaftNode node : nodes) {
            if (node.state() == RaftState.LEADER
                    && node.id().equals(node.leaderId())) {
                return node;
            }
        }
        throw new IllegalStateException("no metadata leader");
    }

    public List<RaftNode> nodes() {
        return List.copyOf(nodes);
    }

    public static RaftNode awaitLeader(List<RaftNode> nodes, long timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode node : nodes) {
                if (node.state() == RaftState.LEADER
                        && node.id().equals(node.leaderId())) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("no leader within " + timeout + "ms");
    }

    @Override
    public void close() {
        for (RaftNode node : nodes) {
            node.close();
        }
    }
}
