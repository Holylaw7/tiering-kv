package io.tieringkv.cluster;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.cluster.metadata.MetadataClient;
import io.tieringkv.cluster.metadata.MetadataRaftGroup;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.cluster.rpc.NettyRaftTransport;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 13 集成：TCP 批量复制 + 故障转移 / 元数据 Raft / 安全 RPC / 迁移联动。 */
class DistributedOptimizationIntegrationTest {

    @TempDir
    Path dir;

    @Test
    void tcpBatchPipelineWriteAndFailover() throws Exception {
        TcpCluster cluster = tcpCluster("integ-tcp");
        try {
            ClusterNode leader = awaitClusterLeader(cluster);
            for (int i = 0; i < 50; i++) {
                putSync(cluster, bytes("batch:" + i), bytes("v" + i));
            }
            awaitAllApplied(cluster, bytes("batch:49"));
            leader.raft().suspend();
            leader.raft().close();
            cluster.transports.get(leader.id()).close();
            ClusterNode newLeader = awaitClusterLeader(cluster);
            assertThat(newLeader.id()).isNotEqualTo(leader.id());
            assertThat(newLeader.get(bytes("batch:49"))).isNotNull();
        } finally {
            cluster.close();
        }
    }

    @Test
    void metadataRaftLeaderFailureStillWritable() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.create(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10);
        group.start();
        MetadataClient client = new MetadataClient(group);
        awaitLeader(group.nodes(), 5000);
        try {
            RaftNode leader = awaitLeader(group.nodes(), 5000);
            client.join("storage-1");
            leader.suspend();
            leader.close();
            RaftNode newLeader = awaitLeader(group.nodes(), 5000);
            assertThat(newLeader).isNotEqualTo(leader);
            client.join("storage-2");
            assertThat(client.state().nodes().contains("storage-2")).isTrue();
            assertThat(client.state().nodes().contains("storage-1")).isTrue();
        } finally {
            group.close();
        }
    }

    @Test
    void secureTlsAuthRaftTransport() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        long expiry = System.currentTimeMillis() + 60_000;
        RpcSecurityConfig security = new RpcSecurityConfig(true,
                cert.certificate().toPath(), cert.privateKey().toPath(),
                "cluster-token", expiry, 0);
        int portA = freePort();
        int portB = freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "a", new InetSocketAddress("127.0.0.1", portA),
                "b", new InetSocketAddress("127.0.0.1", portB));
        NettyRaftTransport transportA = new NettyRaftTransport("a", portA, addresses, security);
        NettyRaftTransport transportB = new NettyRaftTransport("b", portB, addresses, security);
        List<String> applied = new ArrayList<>();
        RaftNode nodeA = new RaftNode("a", transportA,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                new LeaderElection(100, 80), 25, 10,
                new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, null);
        RaftNode nodeB = new RaftNode("b", transportB,
                (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                new LeaderElection(100, 80), 25, 10,
                new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, null);
        transportA.register("a", nodeA);
        transportB.register("b", nodeB);
        transportA.start();
        transportB.start();
        try {
            io.tieringkv.cluster.raft.AppendEntriesResponse response = transportA
                    .appendEntries("b", new io.tieringkv.cluster.raft.AppendEntriesRequest(
                            1, "a", -1, 0, List.of(
                                    new io.tieringkv.cluster.raft.LogEntry(
                                            1, 0, bytes("secure"))), 0))
                    .get(5, TimeUnit.SECONDS);
            assertThat(response.success()).isTrue();
        } finally {
            transportA.close();
            transportB.close();
        }
    }

    @Test
    void slotMigrationUpdatesMetadataStatus() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.create(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10);
        group.start();
        MetadataClient client = new MetadataClient(group);
        awaitLeader(group.nodes(), 5000);
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            for (int i = 0; i < 100; i++) {
                source.put(bytes("mig:" + i), bytes("v" + i));
            }
            client.migrationStatus(0, "INIT");
            io.tieringkv.cluster.migration.MigrationTask task =
                    new io.tieringkv.cluster.migration.MigrationTask(
                            "integ", 0, 16_383, 1, source, target);
            io.tieringkv.cluster.migration.SlotMigrationManager manager =
                    new io.tieringkv.cluster.migration.SlotMigrationManager(
                            client.state().topology().slotTable(),
                            dir.resolve("migration-cursor"));
            manager.start(task);
            client.migrationStatus(0, "COPYING");
            io.tieringkv.cluster.migration.MigrationState state = task.state();
            while (state != io.tieringkv.cluster.migration.MigrationState.DONE) {
                state = manager.runBatch(task, 1000);
            }
            client.migrationStatus(0, "DONE");
            assertThat(client.state().migrationStatus(0)).isEqualTo("DONE");
            assertThat(target.size()).isEqualTo(100);
        } finally {
            source.close();
            target.close();
            group.close();
        }
    }

    @Test
    void pipelineThroughputOverTcp() throws Exception {
        TcpCluster cluster = tcpCluster("integ-bench");
        try {
            ClusterNode leader = awaitClusterLeader(cluster);
            int threads = 8;
            int perThread = 250;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();
            long start = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                Thread worker = new Thread(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            putSync(cluster, bytes("p" + (base + i)), bytes("v"));
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
                worker.start();
            }
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            int total = threads * perThread;
            double opsPerSecond = total / seconds;
            System.out.printf(java.util.Locale.ROOT,
                    "P13-INTEG TCP pipeline ops=%d time=%.2fs ops/s=%.0f failures=%d%n",
                    total, seconds, opsPerSecond, failures.get());
            assertThat(failures).hasValue(0);
            assertThat(opsPerSecond).isGreaterThan(1000);
            awaitAllApplied(cluster, bytes("p" + (total - 1)));
        } finally {
            cluster.close();
        }
    }

    private TcpCluster tcpCluster(String prefix) throws Exception {
        Map<String, Integer> ports = Map.of("n1", freePort(), "n2", freePort(), "n3", freePort());
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", ports.get("n1")),
                "n2", new InetSocketAddress("127.0.0.1", ports.get("n2")),
                "n3", new InetSocketAddress("127.0.0.1", ports.get("n3")));
        LeaderElection election = new LeaderElection(100, 80);
        Map<String, ClusterNode> nodes = new HashMap<>();
        Map<String, NettyRaftTransport> transports = new HashMap<>();
        for (String id : List.of("n1", "n2", "n3")) {
            Path nodeDir = dir.resolve(prefix + "-" + id);
            MemTable local = MemTable.create();
            FileRaftLog log = FileRaftLog.open(nodeDir.resolve("log"), Durability.ASYNC);
            RaftPersistentState state = RaftPersistentState.open(nodeDir);
            SnapshotManager snapshot = SnapshotManager.open(nodeDir.resolve("snapshot"),
                    () -> StorageSnapshotCodec.serialize(local),
                    data -> StorageSnapshotCodec.restore(local, data));
            NettyRaftTransport transport = new NettyRaftTransport(
                    id, ports.get(id), addresses);
            ClusterNode node = ClusterNode.createPersistent(
                    id, transport, local, election, 25, 10, log, state, snapshot);
            transport.register(id, node.raft());
            transport.start();
            node.start();
            nodes.put(id, node);
            transports.put(id, transport);
        }
        return new TcpCluster(nodes, transports);
    }

    private static ClusterNode awaitClusterLeader(TcpCluster cluster)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterNode node : cluster.nodes.values()) {
                if (node.raft().state() == RaftState.LEADER) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no leader");
    }

    /** 通过当前 leader 提案；leader 变更时重试新 leader。 */
    private static void putSync(TcpCluster cluster, byte[] key, byte[] value) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            ClusterNode leader = awaitClusterLeader(cluster);
            try {
                leader.put(key, value);
                return;
            } catch (Exception e) {
                Thread.sleep(10);
            }
        }
        throw new AssertionError("propose failed after retries");
    }

    private static void awaitAllApplied(TcpCluster cluster, byte[] key)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
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
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record TcpCluster(Map<String, ClusterNode> nodes,
                              Map<String, NettyRaftTransport> transports)
            implements AutoCloseable {
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
