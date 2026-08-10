package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Raft 测试工具：节点构建与等待辅助。 */
public final class RaftTestSupport {

    public static final LeaderElection ELECTION = new LeaderElection(100, 80);

    private RaftTestSupport() {
    }

    public static RaftNode node(String id, List<RaftNode> peers, List<String> applied) {
        return new RaftNode(id, peers,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                ELECTION, 25, 10);
    }

    public static RaftNode[] group3(List<String> applied) {
        RaftNode[] nodes = new RaftNode[3];
        List<RaftNode> peers = new ArrayList<>();
        nodes[0] = node("n1", peers, applied);
        nodes[1] = node("n2", peers, applied);
        nodes[2] = node("n3", peers, applied);
        peers.addAll(List.of(nodes));
        return nodes;
    }

    public static void startAll(RaftNode... nodes) {
        for (RaftNode node : nodes) {
            node.start();
        }
    }

    public static RaftNode awaitLeader(List<RaftNode> nodes, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (RaftNode node : nodes) {
                if (node.state() == RaftState.LEADER) {
                    // 等待集群稳定：所有节点 term 一致且无 CANDIDATE，
                    // 避免"旧 term leader + 新 term 选举"竞态导致提案被拒绝
                    long term = node.currentTerm();
                    boolean stable = true;
                    for (RaftNode peer : nodes) {
                        if (peer.active()
                                && (peer.state() == RaftState.CANDIDATE
                                || peer.currentTerm() != term)) {
                            stable = false;
                            break;
                        }
                    }
                    if (stable) {
                        return node;
                    }
                    break;
                }
            }
            Thread.sleep(10);
        }
        List<String> states = new ArrayList<>();
        for (RaftNode node : nodes) {
            states.add(node.id() + "=" + node.state());
        }
        throw new AssertionError("no leader within " + timeoutMillis + "ms: " + states);
    }

    public static void awaitTrue(String message, BooleanSupplier condition, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as(message).isTrue();
    }

    public static void closeAll(RaftNode... nodes) {
        for (RaftNode node : nodes) {
            node.close();
        }
    }
}
