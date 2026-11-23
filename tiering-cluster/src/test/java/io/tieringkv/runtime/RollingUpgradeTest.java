package io.tieringkv.runtime;

import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 滚动升级（ADR-0098）：quorum 保持、无事务丢失。 */
class RollingUpgradeTest {

    @Test
    void rollingUpgradeAllNodes() throws Exception {
        List<RaftNode> peers = new ArrayList<>();
        List<RaftNode> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            RaftNode node = new RaftNode("n" + i, peers,
                    (index, command) -> {
                    }, new LeaderElection(100, 80), 25, 10);
            nodes.add(node);
            peers.add(node);
        }
        for (RaftNode node : nodes) {
            node.start();
        }
        AtomicInteger upgraded = new AtomicInteger();
        AtomicBoolean healthy = new AtomicBoolean(true);
        boolean done = UpgradeCoordinator.rollingUpgrade(nodes,
                node -> {
                    node.suspend();
                    node.close();
                    upgraded.incrementAndGet();
                },
                healthy::get, () -> true, 5_000);
        assertThat(done).isTrue();
        assertThat(upgraded.get()).isEqualTo(3);
    }

    @Test
    void quorumLossAbortsUpgrade() throws Exception {
        AtomicInteger upgraded = new AtomicInteger();
        AtomicBoolean quorum = new AtomicBoolean(true);
        boolean done = UpgradeCoordinator.rollingUpgrade(
                List.of("a", "b", "c"), node -> {
                    if (upgraded.incrementAndGet() == 2) {
                        quorum.set(false);
                    }
                }, quorum::get, () -> true, 100);
        assertThat(done).isFalse();
        assertThat(upgraded.get()).isEqualTo(2);
    }

    @Test
    void catchupTimeoutAborts() {
        boolean done = UpgradeCoordinator.rollingUpgrade(
                List.of("a"), node -> {
                }, () -> true, () -> false, 50);
        assertThat(done).isFalse();
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 2, 3, 5, 8})
    void parameterizedUpgrade(int nodeCount) {
        AtomicInteger upgraded = new AtomicInteger();
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            nodes.add("n" + i);
        }
        boolean done = UpgradeCoordinator.rollingUpgrade(nodes,
                node -> upgraded.incrementAndGet(),
                () -> true, () -> true, 100);
        assertThat(done).isTrue();
        assertThat(upgraded.get()).isEqualTo(nodeCount);
    }

    @ParameterizedTest(name = "wait {0}")
    @ValueSource(ints = {10, 50, 100, 200})
    void parameterizedCatchupWait(int waitMillis) {
        boolean done = UpgradeCoordinator.rollingUpgrade(
                List.of("a", "b"), node -> {
                }, () -> true,
                () -> waitMillis >= 100, waitMillis);
        // 追平条件在 waitMillis>=100 才满足：短超时必须中止升级。
        assertThat(done).isEqualTo(waitMillis >= 100);
    }
}
