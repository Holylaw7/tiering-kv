package io.tieringkv.benchmark.production;

import io.tieringkv.cluster.metadata.MetadataClient;
import io.tieringkv.cluster.metadata.MetadataRaftGroup;
import io.tieringkv.cluster.migration.MigrationState;
import io.tieringkv.cluster.migration.MigrationTask;
import io.tieringkv.cluster.migration.SlotMigrationManager;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.ReplicationController;
import io.tieringkv.cluster.raft.RaftReplicationConfig;
import io.tieringkv.cluster.rpc.RequestId;
import io.tieringkv.cluster.rpc.RpcClient;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.cluster.rpc.RpcServer;
import io.tieringkv.cluster.rpc.security.RpcSecurityConfig;
import io.tieringkv.cluster.rpc.security.HmacConfig;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.SlotTable;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.tieringkv.cluster.RaftTestSupport.awaitLeader;
import static org.assertj.core.api.Assertions.assertThat;

/** Phase 14 基准：批量迁移 / 自适应复制 / HMAC / 元数据重启。 */
@Tag("benchmark")
class ProductionHardeningBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void migration100bBatchThroughput() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            int entries = 200_000;
            byte[] value = new byte[100];
            Arrays.fill(value, (byte) 'v');
            for (int i = 0; i < entries; i++) {
                source.put(bytes("mig:" + i), value);
            }
            SlotTable slotTable = new SlotTable();
            slotTable.assignShards(2);
            MigrationTask task = new MigrationTask("p14", 0,
                    HashSlotRouter.SLOT_COUNT - 1, 1, source, target);
            SlotMigrationManager manager = new SlotMigrationManager(slotTable,
                    dir.resolve("p14-cursor"));
            manager.start(task);
            long start = System.nanoTime();
            MigrationState state = task.state();
            while (state != MigrationState.DONE) {
                state = manager.runBatch(task, 50_000);
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            double mb = entries * 100.0 / 1024 / 1024;
            printf("P14-BENCH MIGRATION 100B entries=%d throughput=%.1fMB/s (%.0f entries/s)%n",
                    entries, mb / seconds, entries / seconds);
            assertThat(target.size()).isEqualTo(entries);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void adaptiveReplicationThroughput() throws Exception {
        List<String> applied = new ArrayList<>();
        List<RaftNode> peers = new ArrayList<>();
        ReplicationController controller = ReplicationController.defaults();
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RaftNode node = new RaftNode(id,
                    new io.tieringkv.cluster.raft.LocalRaftTransport(peers, id),
                    (index, command) -> applied.add(new String(command, StandardCharsets.UTF_8)),
                    new LeaderElection(100, 80), 25, 10,
                    new io.tieringkv.cluster.raft.log.MemoryRaftLog(), null, null,
                    RaftReplicationConfig.defaults(), controller);
            nodes.add(node);
        }
        peers.addAll(nodes);
        nodes.forEach(RaftNode::start);
        try {
            RaftNode leader = awaitLeader(nodes, 5000);
            int threads = 64;
            int perThread = 100;
            int total = threads * perThread;
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger();
            long start = System.nanoTime();
            for (int t = 0; t < threads; t++) {
                int base = t * perThread;
                new Thread(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            leader.propose(bytes("k" + (base + i))).get(5, TimeUnit.SECONDS);
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }
            assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            printf("P14-BENCH RAFT ADAPTIVE writes=%d ops/s=%.0f failures=%d%n",
                    total, total / seconds, failures.get());
            assertThat(failures).hasValue(0);
            assertThat(total / seconds).isGreaterThan(1000);
        } finally {
            nodes.forEach(RaftNode::close);
        }
    }

    @Test
    void hmacOverhead() throws Exception {
        int port;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        RpcServer server = new RpcServer(port, RpcSecurityConfig.disabled(), null, null);
        server.handler(frame -> new RpcFrame(frame.requestId(),
                RpcMessageType.REQUEST_VOTE_RESPONSE, frame.payload()));
        server.start();
        RpcClient plain = new RpcClient(RpcSecurityConfig.disabled(), null, null);
        RpcClient hmac = new RpcClient(RpcSecurityConfig.disabled(), null,
                HmacConfig.single("node-a", "key"));
        try {
            InetSocketAddress address = new InetSocketAddress("127.0.0.1", port);
            byte[] payload = bytes("bench");
            long plainStart = System.nanoTime();
            for (int i = 0; i < 5000; i++) {
                plain.call(address, new RpcFrame(RequestId.next().value(),
                        RpcMessageType.REQUEST_VOTE, payload), 3000, 0)
                        .get(3, TimeUnit.SECONDS);
            }
            long plainNanos = System.nanoTime() - plainStart;
            RpcServer hmacServer = new RpcServer(freePort(), RpcSecurityConfig.disabled(),
                    null, HmacConfig.single("node-a", "key"));
            hmacServer.handler(frame -> new RpcFrame(frame.requestId(),
                    RpcMessageType.REQUEST_VOTE_RESPONSE, frame.payload()));
            hmacServer.start();
            RpcClient hmacClient = new RpcClient(RpcSecurityConfig.disabled(), null,
                    HmacConfig.single("node-a", "key"));
            try {
                InetSocketAddress hmacAddress =
                        new InetSocketAddress("127.0.0.1", hmacServer.boundPort());
                long hmacStart = System.nanoTime();
                for (int i = 0; i < 5000; i++) {
                    hmacClient.call(hmacAddress, new RpcFrame(RequestId.next().value(),
                            RpcMessageType.REQUEST_VOTE, payload), 3000, 0)
                            .get(3, TimeUnit.SECONDS);
                }
                long hmacNanos = System.nanoTime() - hmacStart;
                printf("P14-BENCH HMAC plain=%.1fus hmac=%.1fus overhead=%.0f%%%n",
                        plainNanos / 5000.0 / 1000.0, hmacNanos / 5000.0 / 1000.0,
                        (hmacNanos - plainNanos) * 100.0 / plainNanos);
            } finally {
                hmacClient.close();
                hmacServer.close();
            }
            hmac.close();
            plain.close();
            server.close();
        } finally {
            plain.close();
            server.close();
        }
    }

    @Test
    void metadataRestartTime() throws Exception {
        MetadataRaftGroup first = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10,
                dir.resolve("meta"));
        first.start();
        MetadataClient client = new MetadataClient(first);
        awaitLeader(first.nodes(), 5000);
        for (int i = 0; i < 50; i++) {
            client.join("node-" + i);
        }
        first.close();
        long start = System.currentTimeMillis();
        MetadataRaftGroup second = MetadataRaftGroup.createPersistent(
                List.of("m1", "m2", "m3"), new LeaderElection(100, 80), 25, 10,
                dir.resolve("meta"));
        second.start();
        try {
            RaftNode leader = awaitLeader(second.nodes(), 5000);
            new MetadataClient(second).join("after");
            long restartMs = System.currentTimeMillis() - start;
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && (!second.state(leader.id()).nodes().contains("node-49")
                    || !second.state(leader.id()).nodes().contains("after"))) {
                Thread.sleep(20);
            }
            assertThat(second.state(leader.id()).nodes().contains("node-49")).isTrue();
            printf("P14-BENCH METADATA RESTART=%dms%n", restartMs);
        } finally {
            second.close();
        }
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
}
