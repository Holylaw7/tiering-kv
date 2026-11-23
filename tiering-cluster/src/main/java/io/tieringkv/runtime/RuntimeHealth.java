package io.tieringkv.runtime;

import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;

import java.util.List;
import java.util.Locale;

/** 运行时健康探针（ADR-0096）：/health /readiness /liveness 数据源。 */
public final class RuntimeHealth {

    private final List<RaftNode> raftNodes;
    private final java.util.function.Supplier<Integer> pendingTxn;
    private final java.util.function.Supplier<Integer> lockCount;

    public RuntimeHealth(List<RaftNode> raftNodes,
                         java.util.function.Supplier<Integer> pendingTxn,
                         java.util.function.Supplier<Integer> lockCount) {
        this.raftNodes = List.copyOf(raftNodes);
        this.pendingTxn = pendingTxn;
        this.lockCount = lockCount;
    }

    public boolean liveness() {
        return true; // 进程存活
    }

    public boolean readiness() {
        return raftNodes.stream().anyMatch(node ->
                node.state() == RaftState.LEADER
                        && node.id().equals(node.leaderId()));
    }

    public String json() {
        RaftNode leader = raftNodes.stream()
                .filter(node -> node.state() == RaftState.LEADER
                        && node.id().equals(node.leaderId()))
                .findFirst().orElse(null);
        return String.format(Locale.ROOT,
                "{\"health\":\"ok\",\"readiness\":%b,"
                        + "\"leader\":\"%s\",\"term\":%d,"
                        + "\"pending_txn\":%d,\"lock_count\":%d}",
                readiness(), leader == null ? "none" : leader.id(),
                leader == null ? 0 : leader.currentTerm(),
                pendingTxn.get(), lockCount.get());
    }
}
