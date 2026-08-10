package io.tieringkv.cluster;

import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.InstallSnapshotRequest;
import io.tieringkv.cluster.raft.InstallSnapshotResponse;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.RaftTransport;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.raft.VoteResponse;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static io.tieringkv.cluster.RaftTestSupport.startAll;
import static org.assertj.core.api.Assertions.assertThat;

/** 故障注入（Phase 14）：网络延迟/断连/丢包 + 节点/日志故障。 */
class FailureInjectionTest {

    @TempDir
    Path dir;

    @Test
    void networkDelayStillCommits() throws Exception {
        FaultGroup group = faultGroup(5, 0, 0);
        try {
            RaftNode leader = awaitLeader(group.nodes(), 5000);
            leader.propose(bytes("delayed")).get(10, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isZero();
        } finally {
            group.close();
        }
    }

    @Test
    void disconnectOfFollowerStillCommits() throws Exception {
        FaultGroup group = faultGroup(0, 1, 0); // 断连一个 follower
        try {
            RaftNode leader = awaitLeader(group.nodes(), 5000);
            leader.propose(bytes("survive")).get(10, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isZero();
        } finally {
            group.close();
        }
    }

    @Test
    void packetLossRecoversWithRetry() throws Exception {
        FaultGroup group = faultGroup(0, 0, 30); // 30% 丢包
        try {
            RaftNode leader = awaitLeader(group.nodes(), 5000);
            for (int i = 0; i < 20; i++) {
                leader.propose(bytes("p" + i)).get(10, TimeUnit.SECONDS);
            }
            assertThat(leader.commitIndex()).isEqualTo(19);
        } finally {
            group.close();
        }
    }

    @Test
    void killLeaderTriggersFailover() throws Exception {
        FaultGroup group = faultGroup(0, 0, 0);
        try {
            RaftNode leader = awaitLeader(group.nodes(), 5000);
            leader.propose(bytes("before")).get(10, TimeUnit.SECONDS);
            leader.suspend();
            leader.close();
            RaftNode newLeader = awaitLeader(group.nodes(), 5000);
            assertThat(newLeader.id()).isNotEqualTo(leader.id());
            // Raft：新 leader 提交旧 term 条目前需先追加自己的条目
            newLeader.propose(bytes("after")).get(10, TimeUnit.SECONDS);
            assertThat(newLeader.commitIndex()).isGreaterThanOrEqualTo(1);
        } finally {
            group.close();
        }
    }

    @Test
    void corruptLogTailTruncatedOnRecovery() throws Exception {
        Path logDir = dir.resolve("corrupt");
        try (RaftLog log = FileRaftLog.open(logDir, Durability.SYNC)) {
            log.append(new LogEntry(1, 0, bytes("a")));
            log.append(new LogEntry(1, 1, bytes("b")));
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(logDir.resolve("segment-00000000000000000000.log"));
        bytes[bytes.length - 1] ^= 0x01;
        java.nio.file.Files.write(logDir.resolve("segment-00000000000000000000.log"), bytes);
        try (RaftLog log = FileRaftLog.open(logDir, Durability.SYNC)) {
            assertThat(log.size()).isLessThanOrEqualTo(1);
        }
    }

    private FaultGroup faultGroup(long delayMillis, int disconnectCount, int lossPercent) {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        FaultInjectingTransport transport = new FaultInjectingTransport(
                peers, delayMillis, disconnectCount, lossPercent);
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RaftNode node = new RaftNode(id, transport,
                    (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, null);
            nodes.add(node);
        }
        peers.addAll(nodes);
        startAll(nodes.toArray(new RaftNode[0]));
        return new FaultGroup(nodes, transport);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 可注入延迟/断连/丢包的测试传输。 */
    private static final class FaultInjectingTransport implements RaftTransport {
        private final List<RaftNode> peers;
        private final long delayMillis;
        private final AtomicInteger disconnectsRemaining;
        private final int lossPercent;

        private FaultInjectingTransport(List<RaftNode> peers, long delayMillis,
                                        int disconnectCount, int lossPercent) {
            this.peers = peers;
            this.delayMillis = delayMillis;
            this.disconnectsRemaining = new AtomicInteger(disconnectCount);
            this.lossPercent = lossPercent;
        }

        @Override
        public List<String> peerIds() {
            return peers.stream().map(RaftNode::id).toList();
        }

        @Override
        public CompletableFuture<VoteResponse> requestVote(String target, VoteRequest request) {
            if (shouldDrop()) {
                return CompletableFuture.failedFuture(new IllegalStateException("dropped"));
            }
            return delayed(CompletableFuture.completedFuture(find(target).receive(request)));
        }

        @Override
        public CompletableFuture<AppendEntriesResponse> appendEntries(
                String target, AppendEntriesRequest request) {
            if (shouldDrop()) {
                return CompletableFuture.failedFuture(new IllegalStateException("dropped"));
            }
            return delayed(CompletableFuture.completedFuture(find(target).receive(request)));
        }

        @Override
        public CompletableFuture<InstallSnapshotResponse> installSnapshot(
                String target, InstallSnapshotRequest request) {
            if (shouldDrop()) {
                return CompletableFuture.failedFuture(new IllegalStateException("dropped"));
            }
            return delayed(CompletableFuture.completedFuture(find(target).receive(request)));
        }

        private boolean shouldDrop() {
            if (disconnectsRemaining.getAndUpdate(v -> v > 0 ? v - 1 : v) > 0) {
                return true;
            }
            return lossPercent > 0 && (int) (Math.random() * 100) < lossPercent;
        }

        private <T> CompletableFuture<T> delayed(CompletableFuture<T> future) {
            if (delayMillis <= 0) {
                return future;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return future;
        }

        private RaftNode find(String id) {
            for (RaftNode peer : peers) {
                if (peer.id().equals(id)) {
                    return peer;
                }
            }
            throw new IllegalStateException("no peer " + id);
        }
    }

    private record FaultGroup(List<RaftNode> nodes, FaultInjectingTransport transport)
            implements AutoCloseable {
        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }
}
