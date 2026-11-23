package io.tieringkv.benchmark.distributed;

import io.tieringkv.cluster.ClusterNode;
import io.tieringkv.cluster.StorageSnapshotCodec;
import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.migration.MigrationState;
import io.tieringkv.cluster.migration.MigrationTask;
import io.tieringkv.cluster.migration.SlotMigrationManager;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.cluster.rpc.NettyRaftTransport;
import io.tieringkv.cluster.rpc.RpcClient;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcServer;
import io.tieringkv.cluster.rpc.RequestId;
import io.tieringkv.cluster.sharding.ShardId;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式生产基准（Phase 12）：RaftLog 追加延迟、TCP 集群提交延迟与复制滞后、
 * RPC 吞吐/连接数、迁移吞吐与恢复时间；与 Phase 11 进程内基线对比。
 */
@Tag("benchmark")
class DistributedProductionBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void raftLogAppendLatency() throws Exception {
        Path logDir = dir.resolve("bench-log");
        try (RaftLog log = FileRaftLog.open(logDir, Durability.ASYNC)) {
            long[] latencyUs = new long[100_000];
            long start = System.nanoTime();
            for (int i = 0; i < latencyUs.length; i++) {
                long t0 = System.nanoTime();
                log.append(new io.tieringkv.cluster.raft.LogEntry(1, i, bytes("cmd")));
                latencyUs[i] = (System.nanoTime() - t0) / 1_000;
            }
            log.sync();
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Arrays.sort(latencyUs);
            long[] p = percentiles(latencyUs);
            printf("DP-BENCH RAFT-LOG ASYNC append=%d ops/s=%.0f p50=%.2fus p95=%.2fus p99=%.2fus%n",
                    latencyUs.length, latencyUs.length / seconds,
                    (double) p[0], (double) p[1], (double) p[2]);
            assertThat(seconds).isLessThan(30);
        }
    }

    @Test
    void tcpClusterCommitLatencyAndReplicationLag() throws Exception {
        TcpCluster cluster = startTcpCluster("bench-tcp");
        try {
            ClusterNode leader = awaitLeader(cluster);
            int writes = 2_000;
            long[] latencyUs = new long[writes];
            long[] lagSamples = new long[20];
            long start = System.nanoTime();
            for (int i = 0; i < writes; i++) {
                long t0 = System.nanoTime();
                leader.put(bytes("bench:" + i), bytes("value"));
                latencyUs[i] = (System.nanoTime() - t0) / 1_000;
                if (i % 100 == 99) {
                    int sample = i / 100;
                    long commit = System.nanoTime();
                    while (!allApplied(cluster, leader) && System.nanoTime() - commit < 1_000_000_000L) {
                        Thread.sleep(1);
                    }
                    lagSamples[sample] = (System.nanoTime() - commit) / 1_000_000;
                }
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Arrays.sort(latencyUs);
            Arrays.sort(lagSamples);
            long[] p = percentiles(latencyUs);
            printf("DP-BENCH TCP COMMIT writes=%d ops/s=%.0f p50=%.3fms p95=%.3fms p99=%.3fms%n",
                    writes, writes / seconds, p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0);
            printf("DP-BENCH TCP REPLICATION-LAG samples=%d p50=%.1fms p99=%.1fms max=%.1fms%n",
                    lagSamples.length, (double) lagSamples[10], (double) lagSamples[19],
                    (double) lagSamples[19]);
            assertThat(seconds).isLessThan(60);
            assertThat(lagSamples[19]).isLessThan(1000);
        } finally {
            cluster.close();
        }
    }

    @Test
    void rpcThroughputAndConnections() throws Exception {
        int port = freePort();
        RpcServer server = new RpcServer(port);
        server.handler(frame -> new RpcFrame(frame.requestId(),
                RpcMessageType.APPEND_ENTRIES_RESPONSE, frame.payload()));
        server.start();
        RpcClient client = new RpcClient();
        try {
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
            int calls = 20_000;
            long[] latencyUs = new long[calls];
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                long t0 = System.nanoTime();
                client.call(address, new RpcFrame(RequestId.next().value(),
                        RpcMessageType.APPEND_ENTRIES, bytes("payload")), 3000, 0)
                        .get(3, TimeUnit.SECONDS);
                latencyUs[i] = (System.nanoTime() - t0) / 1_000;
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Arrays.sort(latencyUs);
            long[] p = percentiles(latencyUs);
            printf("DP-BENCH RPC calls=%d ops/s=%.0f p50=%.2fus p95=%.2fus p99=%.2fus connections=%d%n",
                    calls, calls / seconds, (double) p[0], (double) p[1], (double) p[2],
                    client.connectionCount());
            assertThat(client.connectionCount()).isEqualTo(1);
            assertThat(seconds).isLessThan(60);
        } finally {
            client.close();
            server.close();
        }
    }

    @Test
    void migrationThroughputAndRecoveryTime() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            int entries = 100_000;
            byte[] value = new byte[100];
            Arrays.fill(value, (byte) 'v');
            for (int i = 0; i < entries; i++) {
                source.put(bytes("mig:" + i), value);
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            Path checkpointDir = dir.resolve("bench-migration");
            MigrationTask task = new MigrationTask("bench", 0, 16_383, 1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, checkpointDir);
            manager.start(task);
            long start = System.nanoTime();
            MigrationState state = task.state();
            while (state != MigrationState.DONE) {
                state = manager.runBatch(task, 50_000);
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            double mb = entries * 100.0 / 1024 / 1024;
            printf("DP-BENCH MIGRATION entries=%d size=%.1fMB throughput=%.1fMB/s%n",
                    entries, mb, mb / seconds);
            assertThat(seconds).isLessThan(60);

            // 恢复时间：部分复制后从 checkpoint 续传
            MemTable source2 = MemTable.create();
            MemTable target2 = MemTable.create();
            try {
                for (int i = 0; i < entries; i++) {
                    source2.put(bytes("mig:" + i), value);
                }
                MigrationTask partial = new MigrationTask("recover", 0, 16_383, 1,
                        source2, target2);
                SlotMigrationManager first = new SlotMigrationManager(slotTable, checkpointDir);
                first.start(partial);
                first.runBatch(partial, 10_000);
                SlotMigrationManager second = new SlotMigrationManager(slotTable, checkpointDir);
                MigrationTask resumed = new MigrationTask("recover", 0, 16_383, 1,
                        source2, target2);
                second.resume(resumed);
                long recoverStart = System.nanoTime();
                MigrationState resumedState = resumed.state();
                while (resumedState != MigrationState.DONE) {
                    resumedState = second.runBatch(resumed, 50_000);
                }
                double recoverSeconds = (System.nanoTime() - recoverStart) / 1_000_000_000.0;
                printf("DP-BENCH MIGRATION-RECOVERY remaining=%d time=%.0fms%n",
                        entries - 10_000, recoverSeconds * 1000);
                assertThat(recoverSeconds).isLessThan(60);
            } finally {
                source2.close();
                target2.close();
            }
        } finally {
            source.close();
            target.close();
        }
    }

    private TcpCluster startTcpCluster(String prefix) throws Exception {
        Map<String, Integer> ports = Map.of("n1", freePort(), "n2", freePort(), "n3", freePort());
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", ports.get("n1")),
                "n2", new InetSocketAddress("127.0.0.1", ports.get("n2")),
                "n3", new InetSocketAddress("127.0.0.1", ports.get("n3")));
        MetadataServer metadata = new MetadataServer();
        metadata.join("n1", 1);
        metadata.join("n2", 1);
        metadata.join("n3", 1);
        metadata.createShard(new ShardId(0), List.of("n1", "n2", "n3"), null);
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

    private static boolean allApplied(TcpCluster cluster, ClusterNode leader) {
        for (ClusterNode node : cluster.nodes.values()) {
            if (node.raft().lastApplied() < leader.raft().commitIndex()) {
                return false;
            }
        }
        return true;
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
        throw new AssertionError("no leader");
    }

    private static long[] percentiles(long[] sorted) {
        return new long[]{
                sorted[(int) (sorted.length * 0.50)],
                sorted[(int) (sorted.length * 0.95)],
                sorted[(int) (sorted.length * 0.99)]
        };
    }

    private static int freePort() throws Exception {
        return io.tieringkv.testkit.TestPorts.freePort();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record TcpCluster(Map<String, ClusterNode> nodes,
                              Map<String, NettyRaftTransport> transports) implements AutoCloseable {
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
