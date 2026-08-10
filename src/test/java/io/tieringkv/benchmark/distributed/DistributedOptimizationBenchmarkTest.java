package io.tieringkv.benchmark.distributed;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.cluster.metadata.MetadataClient;
import io.tieringkv.cluster.metadata.MetadataRaftGroup;
import io.tieringkv.cluster.migration.MigrationState;
import io.tieringkv.cluster.migration.MigrationTask;
import io.tieringkv.cluster.migration.SlotMigrationManager;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.AppendEntriesResponse;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftReplicationConfig;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.rpc.NettyRaftTransport;
import io.tieringkv.cluster.rpc.RequestId;
import io.tieringkv.cluster.rpc.RpcClient;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.cluster.rpc.RpcServer;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.sharding.HashSlotRouter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 13 基准：批量复制吞吐/延迟、游标迁移、RPC 安全开销、元数据故障转移。 */
@Tag("benchmark")
class DistributedOptimizationBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void replicationThroughputOverTcp() throws Exception {
        RaftCluster cluster = raftCluster("bench-raft",
                new RaftReplicationConfig(256, 1 << 20, 1, 16));
        try {
            RaftNode leader = cluster.leader();
            int threads = 64;
            int perThread = 80;
            int total = threads * perThread;
            long[] latenciesUs = new long[total];
            AtomicInteger slot = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();
            long start = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                Thread worker = new Thread(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            int index = slot.getAndIncrement();
                            long t0 = System.nanoTime();
                            proposeSync(cluster, bytes("key-" + index));
                            latenciesUs[index] = (System.nanoTime() - t0) / 1_000;
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
                worker.start();
            }
            assertThat(latch.await(120, TimeUnit.SECONDS)).isTrue();
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Arrays.sort(latenciesUs);
            long[] p = percentiles(latenciesUs);
            printf("P13-BENCH REPLICATION TCP writes=%d ops/s=%.0f p50=%.2fms p95=%.2fms p99=%.2fms failures=%d%n",
                    total, total / seconds, p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0,
                    failures.get());
            // 复制滞后：提交后 follower 应用
            long commit = System.nanoTime();
            while (!allApplied(cluster) && System.nanoTime() - commit < 2_000_000_000L) {
                Thread.sleep(1);
            }
            long lagMs = (System.nanoTime() - commit) / 1_000_000;
            printf("P13-BENCH REPLICATION LAG lastBatch=%dms%n", lagMs);
            assertThat(failures).hasValue(0);
            assertThat(total / seconds).isGreaterThan(500);
        } finally {
            cluster.close();
        }
    }

    @Test
    void cursorMigrationThroughputAndResume() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            int entries = 200_000;
            byte[] value = new byte[1024];
            Arrays.fill(value, (byte) 'v');
            for (int i = 0; i < entries; i++) {
                source.put(bytes("mig:" + i), value);
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            Path cursorDir = dir.resolve("bench13-cursor");
            MigrationTask task = new MigrationTask("bench13", 0,
                    HashSlotRouter.SLOT_COUNT - 1, 1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable, cursorDir);
            manager.start(task);
            long start = System.nanoTime();
            MigrationState state = task.state();
            while (state != MigrationState.DONE) {
                state = manager.runBatch(task, 50_000);
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            double mb = entries * 1024.0 / 1024 / 1024;
            printf("P13-BENCH MIGRATION CURSOR entries=%d size=%.1fMB throughput=%.1fMB/s%n",
                    entries, mb, mb / seconds);
            assertThat(seconds).isLessThan(60);

            // 断点续传
            MemTable source2 = MemTable.create();
            MemTable target2 = MemTable.create();
            try {
                for (int i = 0; i < entries; i++) {
                    source2.put(bytes("mig:" + i), value);
                }
                MigrationTask partial = new MigrationTask("resume13", 0,
                        HashSlotRouter.SLOT_COUNT - 1, 1, source2, target2);
                SlotMigrationManager first = new SlotMigrationManager(slotTable, cursorDir);
                first.start(partial);
                first.runBatch(partial, 20_000);
                MigrationTask recovered = new MigrationTask("resume13", 0,
                        HashSlotRouter.SLOT_COUNT - 1, 1, source2, target2);
                SlotMigrationManager second = new SlotMigrationManager(slotTable, cursorDir);
                second.recover(recovered);
                long resumeStart = System.nanoTime();
                MigrationState resumed = recovered.state();
                while (resumed != MigrationState.DONE) {
                    resumed = second.runBatch(recovered, 50_000);
                }
                double resumeSeconds = (System.nanoTime() - resumeStart) / 1_000_000_000.0;
            printf("P13-BENCH MIGRATION RESUME remaining=%d time=%.0fms%n",
                        entries - 20_000, resumeSeconds * 1000);
                assertThat(target2.size()).isEqualTo(entries);
            } finally {
                source2.close();
                target2.close();
            }
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void rpcTlsAndAuthOverhead() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        long expiry = System.currentTimeMillis() + 60_000;
        RpcSecurityConfig plain = RpcSecurityConfig.disabled();
        RpcSecurityConfig secure = new RpcSecurityConfig(true,
                cert.certificate().toPath(), cert.privateKey().toPath(),
                "bench-token", expiry, 0);

        long plainNanos = measureEcho(plain);
        long secureNanos = measureEcho(secure);
        printf("P13-BENCH RPC plain=%.1fus/call secure=%.1fus/call overhead=%.0f%%%n",
                plainNanos / 10_000.0 / 1000.0, secureNanos / 10_000.0 / 1000.0,
                (secureNanos - plainNanos) * 100.0 / plainNanos);
    }

    @Test
    void metadataWriteThroughputAndFailover() throws Exception {
        MetadataRaftGroup group = MetadataRaftGroup.create(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10);
        group.start();
        MetadataClient client = new MetadataClient(group);
        try {
            RaftNode leader = awaitLeader(group.nodes(), 5000);
            int writes = 2_000;
            long start = System.nanoTime();
            for (int i = 0; i < writes; i++) {
                client.migrationStatus(i % 8, "S" + i);
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            printf("P13-BENCH METADATA WRITE writes=%d ops/s=%.0f%n", writes, writes / seconds);

            leader.suspend();
            leader.close();
            long failoverStart = System.currentTimeMillis();
            RaftNode newLeader = awaitLeader(group.nodes(), 5000);
            long failoverMs = System.currentTimeMillis() - failoverStart;
            printf("P13-BENCH METADATA FAILOVER=%dms newLeader=%s%n",
                    failoverMs, newLeader.id());
            client.join("post-failover");
            assertThat(client.state().nodes().contains("post-failover")).isTrue();
        } finally {
            group.close();
        }
    }

    private long measureEcho(RpcSecurityConfig security) throws Exception {
        int port = freePort();
        RpcServer server = new RpcServer(port, security);
        server.handler(frame -> new RpcFrame(frame.requestId(),
                RpcMessageType.REQUEST_VOTE_RESPONSE, frame.payload()));
        server.start();
        RpcClient client = new RpcClient(security);
        try {
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
            int calls = 10_000;
            byte[] payload = bytes("bench-payload");
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                client.call(address, new RpcFrame(RequestId.next().value(),
                        RpcMessageType.REQUEST_VOTE, payload), 3000, 0)
                        .get(3, TimeUnit.SECONDS);
            }
            return System.nanoTime() - start;
        } finally {
            client.close();
            server.close();
        }
    }

    private RaftCluster raftCluster(String prefix, RaftReplicationConfig config)
            throws Exception {
        Map<String, Integer> ports = Map.of("n1", freePort(), "n2", freePort(), "n3", freePort());
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", ports.get("n1")),
                "n2", new InetSocketAddress("127.0.0.1", ports.get("n2")),
                "n3", new InetSocketAddress("127.0.0.1", ports.get("n3")));
        LeaderElection election = new LeaderElection(100, 80);
        List<RaftNode> nodes = new ArrayList<>();
        Map<String, NettyRaftTransport> transports = new HashMap<>();
        for (String id : List.of("n1", "n2", "n3")) {
            Path nodeDir = dir.resolve(prefix + "-" + id);
            FileRaftLog log = FileRaftLog.open(nodeDir.resolve("log"), Durability.ASYNC);
            RaftPersistentState state = RaftPersistentState.open(nodeDir);
            NettyRaftTransport transport = new NettyRaftTransport(id, ports.get(id), addresses);
            RaftNode node = new RaftNode(id, transport,
                    (index, command) -> {
                    }, election, 25, 10,
                    System.getProperty("bench.noLog") != null
                            ? new MemoryRaftLog() : log,
                    System.getProperty("bench.noLog") != null ? null : state,
                    null, config);
            transport.register(id, node);
            transport.start();
            node.start();
            nodes.add(node);
            transports.put(id, transport);
        }
        RaftNode leader = awaitLeader(nodes, 5000);
        return new RaftCluster(nodes, transports, leader);
    }

    private static void proposeSync(RaftCluster cluster, byte[] command) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            RaftNode leader = cluster.leader();
            try {
                leader.propose(command).get(5, TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                Thread.sleep(2);
            }
        }
        throw new AssertionError("propose failed");
    }

    private static boolean allApplied(RaftCluster cluster) {
        for (RaftNode node : cluster.nodes()) {
            if (node.lastApplied() < 0) {
                return false;
            }
        }
        return true;
    }

    private static long[] percentiles(long[] sorted) {
        return new long[]{
                sorted[(int) (sorted.length * 0.50)],
                sorted[(int) (sorted.length * 0.95)],
                sorted[(int) (sorted.length * 0.99)]
        };
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record RaftCluster(List<RaftNode> nodes,
                               Map<String, NettyRaftTransport> transports,
                               RaftNode leader) implements AutoCloseable {
        @Override
        public void close() {
            for (RaftNode node : nodes) {
                node.close();
            }
            for (NettyRaftTransport transport : transports.values()) {
                transport.close();
            }
        }
    }
}
