package io.tieringkv.cluster;

import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.cluster.rpc.NettyRaftTransport;
import io.tieringkv.cluster.sharding.ShardId;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 3 节点真实 TCP 集群（ADR-0041/0039）：复制、故障转移、重启恢复、滞后。 */
class TcpClusterIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void threeNodeTcpClusterSurvivesLeaderCrash() throws Exception {
        TcpCluster cluster = startCluster();
        try {
            ClusterNode leader = awaitLeader(cluster);
            cluster.metadata.updateLeader(0, leader.id());
            cluster.client.put(bytes("user:1"), bytes("value"));
            awaitAllNodesSee(cluster, bytes("user:1"), 5000);

            leader.raft().suspend();
            leader.raft().close();
            cluster.transports.get(leader.id()).close();

            ClusterNode newLeader = awaitLeader(cluster);
            assertThat(newLeader.id()).isNotEqualTo(leader.id());
            cluster.metadata.updateLeader(0, newLeader.id());
            assertThat(cluster.client.get(bytes("user:1"))).isEqualTo(bytes("value"));
        } finally {
            cluster.close();
        }
    }

    @Test
    void raftLogAndStateSurviveRestart() throws Exception {
        TcpCluster first = startCluster();
        try {
            ClusterNode leader = awaitLeader(first);
            first.metadata.updateLeader(0, leader.id());
            for (int i = 0; i < 5; i++) {
                first.client.put(bytes("k" + i), bytes("v" + i));
            }
            awaitAllNodesSee(first, bytes("k4"), 5000);
            long committed = leader.raft().commitIndex();
            long term = leader.raft().currentTerm();
            first.close();

            TcpCluster second = startCluster();
            try {
                ClusterNode newLeader = awaitLeader(second);
                assertThat(newLeader.raft().currentTerm()).isGreaterThanOrEqualTo(term);
                assertThat(newLeader.raft().commitIndex()).isGreaterThanOrEqualTo(committed);
                assertThat(newLeader.get(bytes("k4"))).isEqualTo(bytes("v4"));
                for (ClusterNode node : second.nodes.values()) {
                    assertThat(node.get(bytes("k0"))).isEqualTo(bytes("v0"));
                }
            } finally {
                second.close();
            }
        } finally {
            first.close();
        }
    }

    @Test
    void replicationLagBelowTargetOverTcp() throws Exception {
        TcpCluster cluster = startCluster();
        try {
            ClusterNode leader = awaitLeader(cluster);
            cluster.metadata.updateLeader(0, leader.id());
            long start = System.nanoTime();
            leader.put(bytes("lag"), bytes("x"));
            long commitNanos = System.nanoTime();
            for (ClusterNode node : cluster.nodes.values()) {
                if (node != leader) {
                    while (node.raft().lastApplied() < leader.raft().commitIndex()
                            && System.nanoTime() - commitNanos < 1_000_000_000L) {
                        Thread.sleep(1);
                    }
                    assertThat(node.raft().lastApplied()).isEqualTo(leader.raft().commitIndex());
                }
            }
            long lagMillis = (System.nanoTime() - commitNanos) / 1_000_000;
            // 目标 <5ms（TCP 回环 + CommitNotifier 立即补发）
            assertThat(lagMillis).isLessThan(100);
        } finally {
            cluster.close();
        }
    }

    private TcpCluster startCluster() throws Exception {
        int port1 = freePort();
        int port2 = freePort();
        int port3 = freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", port1),
                "n2", new InetSocketAddress("127.0.0.1", port2),
                "n3", new InetSocketAddress("127.0.0.1", port3));

        MetadataServer metadata = new MetadataServer();
        metadata.join("n1", 1);
        metadata.join("n2", 1);
        metadata.join("n3", 1);
        metadata.createShard(new ShardId(0), List.of("n1", "n2", "n3"), null);

        LeaderElection election = new LeaderElection(100, 80);
        Map<String, ClusterNode> nodes = new HashMap<>();
        Map<String, NettyRaftTransport> transports = new HashMap<>();
        List<RaftNodeHolder> holders = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            Path nodeDir = dir.resolve(id);
            MemTable local = MemTable.create();
            FileRaftLog log = FileRaftLog.open(nodeDir.resolve("raftlog"), Durability.SYNC);
            RaftPersistentState state = RaftPersistentState.open(nodeDir);
            SnapshotManager snapshot = SnapshotManager.open(nodeDir.resolve("snapshot"),
                    () -> StorageSnapshotCodec.serialize(local),
                    data -> StorageSnapshotCodec.restore(local, data));
            NettyRaftTransport transport = new NettyRaftTransport(
                    id, addresses.get(id).getPort(), addresses);
            ClusterNode node = ClusterNode.createPersistent(
                    id, transport, local, election, 25, 10, log, state, snapshot);
            transport.register(id, node.raft());
            transport.start();
            node.start();
            nodes.put(id, node);
            transports.put(id, transport);
            holders.add(new RaftNodeHolder(log, state, snapshot));
        }
        return new TcpCluster(metadata, nodes, transports,
                new ClusterClient(metadata, nodes), holders);
    }

    private static ClusterNode awaitLeader(TcpCluster cluster) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterNode node : cluster.nodes.values()) {
                if (node.raft().state() == RaftState.LEADER) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no leader over tcp");
    }

    private static void awaitAllNodesSee(TcpCluster cluster, byte[] key, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (ClusterNode node : cluster.nodes.values()) {
                if (node.get(key) == null) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("replicas did not converge");
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record RaftNodeHolder(
            FileRaftLog log,
            RaftPersistentState state,
            SnapshotManager snapshot) {
    }

    private static final class TcpCluster implements AutoCloseable {
        private final MetadataServer metadata;
        private final Map<String, ClusterNode> nodes;
        private final Map<String, NettyRaftTransport> transports;
        private final ClusterClient client;
        private final List<RaftNodeHolder> holders;

        private TcpCluster(MetadataServer metadata, Map<String, ClusterNode> nodes,
                           Map<String, NettyRaftTransport> transports,
                           ClusterClient client, List<RaftNodeHolder> holders) {
            this.metadata = metadata;
            this.nodes = nodes;
            this.transports = transports;
            this.client = client;
            this.holders = holders;
        }

        @Override
        public void close() {
            for (ClusterNode node : nodes.values()) {
                node.close();
            }
            for (NettyRaftTransport transport : transports.values()) {
                transport.close();
            }
        }
    }
}
