package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static io.tieringkv.cluster.RaftTestSupport.awaitTrue;
import static org.assertj.core.api.Assertions.assertThat;

/** RaftNode + Snapshot 集成（ADR-0040）：压缩、重启重放、InstallSnapshot 追赶。 */
class RaftNodeSnapshotIntegrationTest {

    private static final int ENTRIES = 1_100;

    @TempDir
    Path dir;

    @Test
    void snapshotCompactsLogAndRestartReplaysRemaining() throws Exception {
        AtomicReference<byte[]> state = new AtomicReference<>(new byte[0]);
        Path logDir = dir.resolve("solo-log");
        Path stateDir = dir.resolve("solo-state");
        Path snapshotDir = dir.resolve("solo-snapshot");

        RaftNode node = soloNode(logDir, stateDir, snapshotDir, state);
        try {
            node.start();
            awaitTrue("leader", () -> node.state() == RaftState.LEADER, 3000);
            for (int i = 0; i < ENTRIES; i++) {
                node.propose(("e" + i).getBytes(StandardCharsets.UTF_8)).get();
            }
            assertThat(node.logSize()).isLessThan(ENTRIES);
            assertThat(node.commitIndex()).isEqualTo(ENTRIES - 1);
            assertThat(countCommands(state.get())).isEqualTo(ENTRIES);
        } finally {
            node.close();
        }

        // 重启：快照恢复 + 剩余日志重放
        AtomicReference<byte[]> restartedState = new AtomicReference<>(new byte[0]);
        RaftNode restarted = soloNode(logDir, stateDir, snapshotDir, restartedState);
        try {
            assertThat(restarted.currentTerm()).isGreaterThanOrEqualTo(1);
            assertThat(restarted.commitIndex()).isEqualTo(ENTRIES - 1);
            assertThat(restarted.lastApplied()).isEqualTo(ENTRIES - 1);
            assertThat(countCommands(restartedState.get())).isEqualTo(ENTRIES);
        } finally {
            restarted.close();
        }
    }

    @Test
    void installSnapshotRecoversLaggingFollower() throws Exception {
        AtomicReference<byte[]> leaderState = new AtomicReference<>(new byte[0]);
        AtomicReference<byte[]> laggingState = new AtomicReference<>(new byte[0]);

        List<RaftNode> peers = new ArrayList<>();
        RaftNode n1 = persistentNode("n1", dir.resolve("n1-log"),
                dir.resolve("n1-state"), dir.resolve("n1-snapshot"), leaderState, peers);
        RaftNode n2 = persistentNode("n2", dir.resolve("n2-log"),
                dir.resolve("n2-state"), dir.resolve("n2-snapshot"), laggingState, peers);
        RaftNode n3 = persistentNode("n3", dir.resolve("n3-log"),
                dir.resolve("n3-state"), dir.resolve("n3-snapshot"),
                new AtomicReference<>(new byte[0]), peers);
        peers.add(n1);
        peers.add(n2);
        peers.add(n3);
        n3.suspend(); // 模拟离线 follower（日志为空、不参与选举）
        n1.start();
        n2.start();
        n3.start();
        try {
            RaftNode leader = io.tieringkv.cluster.RaftTestSupport.awaitLeader(
                    List.of(n1, n2, n3), 5000);
            for (int i = 0; i < ENTRIES; i++) {
                proposeWithLeaderRetry(leader, n1, n2, n3,
                        ("e" + i).getBytes(StandardCharsets.UTF_8));
            }
            // 慢 Runner 上快照可能在循环结束后才落盘：有界等待任一节点
            // 日志被快照压缩（ADR-0353 测试稳定化）。
            io.tieringkv.cluster.RaftTestSupport.awaitTrue(
                    "snapshot installed (log compacted)",
                    () -> List.of(n1, n2, n3).stream()
                            .anyMatch(n -> n.logSize() < ENTRIES),
                    5000);

            n3.resume();
            awaitTrue("lagging follower catches up", () -> n3.lastApplied() >= ENTRIES - 1, 5000);
            assertThat(countCommands(laggingState.get())).isEqualTo(ENTRIES);
            assertThat(n3.commitIndex()).isEqualTo(leader.commitIndex());
        } finally {
            n1.close();
            n2.close();
            n3.close();
        }
    }

    /** 慢 Runner 上 awaitLeader 返回后可能发生选举切换：
     *  propose 遇 “not leader” 时重新等待 leader 并重试（有界）。 */
    private static void proposeWithLeaderRetry(RaftNode leader,
                                               RaftNode n1, RaftNode n2,
                                               RaftNode n3, byte[] command)
            throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        RaftNode current = leader;
        while (true) {
            try {
                current.propose(command).get(5, java.util.concurrent.TimeUnit.SECONDS);
                return;
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof IllegalStateException
                        && System.currentTimeMillis() < deadline) {
                    current = io.tieringkv.cluster.RaftTestSupport.awaitLeader(
                            List.of(n1, n2, n3), 3000);
                    continue;
                }
                throw e;
            }
        }
    }

    private RaftNode soloNode(Path logDir, Path stateDir, Path snapshotDir,
                              AtomicReference<byte[]> state) throws Exception {
        return persistentNode("solo", logDir, stateDir, snapshotDir, state, List.of());
    }

    private RaftNode persistentNode(String id, Path logDir, Path stateDir,
                                    Path snapshotDir, AtomicReference<byte[]> state,
                                    List<RaftNode> peers) throws Exception {
        FileRaftLog log = FileRaftLog.open(logDir, Durability.SYNC);
        RaftPersistentState persistent = RaftPersistentState.open(stateDir);
        SnapshotManager snapshot = SnapshotManager.open(snapshotDir, state::get, state::set);
        BiConsumer<Long, byte[]> apply = (index, command) -> {
            byte[] current = state.get();
            byte[] next = new byte[current.length + command.length + 1];
            System.arraycopy(current, 0, next, 0, current.length);
            next[current.length] = '|';
            System.arraycopy(command, 0, next, current.length + 1, command.length);
            state.set(next);
        };
        return new RaftNode(id, new LocalRaftTransport(peers, id), apply,
                new LeaderElection(100, 80), 25, 10, log, persistent, snapshot);
    }

    private static int countCommands(byte[] state) {
        int count = 0;
        for (byte b : state) {
            if (b == '|') {
                count++;
            }
        }
        return count;
    }
}
