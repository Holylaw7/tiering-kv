package io.tieringkv.benchmark.cluster;

import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.tieringkv.cluster.ClusterNode;
import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.migration.streaming.StreamingMigrator;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.raft.client.AsyncProposalQueue;
import io.tieringkv.cluster.raft.client.AsyncReplicationClient;
import io.tieringkv.cluster.rpc.security.CertificateManager;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.ShardId;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15 生产验证基准：流式迁移吞吐（100B/1KB/10KB）、全异步 Raft
 * 提案（1/64/256 写者）、混沌恢复时间、TLS 证书轮换延迟。
 * 进程内原型：不含真实网络与磁盘 fsync 路径。
 */
@Tag("benchmark")
class Phase15ProductionValidationBenchmarkTest {

    @TempDir
    Path dir;

    @Test
    void streamingMigrationThroughput100B() throws Exception {
        runMigrationBenchmark(100, 500_000);
    }

    @Test
    void streamingMigrationThroughput1KB() throws Exception {
        runMigrationBenchmark(1024, 150_000);
    }

    @Test
    void streamingMigrationThroughput10KB() throws Exception {
        runMigrationBenchmark(10_240, 20_000);
    }

    @Test
    void asyncRaftSingleWriter() throws Exception {
        RaftBenchmark result = runAsyncRaftThroughput(1, 20_000);
        printf("PHASE15-BENCH ASYNC-RAFT writers=1 ops=%d ops/s=%.0f p50=%.3fms p95=%.3fms p99=%.3fms%n",
                result.ops(), result.opsPerSecond(),
                result.p50Ms(), result.p95Ms(), result.p99Ms());
        assertThat(result.opsPerSecond()).isGreaterThan(100_000);
    }

    @Test
    void asyncRaft64Writers() throws Exception {
        RaftBenchmark result = runAsyncRaftThroughput(64, 2_000);
        printf("PHASE15-BENCH ASYNC-RAFT writers=64 ops=%d ops/s=%.0f p50=%.3fms p95=%.3fms p99=%.3fms%n",
                result.ops(), result.opsPerSecond(),
                result.p50Ms(), result.p95Ms(), result.p99Ms());
        // CI 全量负载下限；报告以实测为准（Phase 22 全量回归波动）
        assertThat(result.opsPerSecond()).isGreaterThan(100_000);
    }

    @Test
    void asyncRaft256Writers() throws Exception {
        RaftBenchmark result = runAsyncRaftThroughput(256, 1_000);
        printf("PHASE15-BENCH ASYNC-RAFT writers=256 ops=%d ops/s=%.0f p50=%.3fms p95=%.3fms p99=%.3fms%n",
                result.ops(), result.opsPerSecond(),
                result.p50Ms(), result.p95Ms(), result.p99Ms());
        assertThat(result.ops()).isEqualTo(256_000);
        // CI 全量负载下限；报告以实测为准（Phase 22 全量回归波动）
        assertThat(result.opsPerSecond()).isGreaterThan(50_000);
    }

    @Test
    void asyncRaftLatencySingleWriter() throws Exception {
        RaftBenchmark result = runAsyncRaftLatency(1, 5_000);
        printf("PHASE15-BENCH ASYNC-RAFT-LATENCY writers=1 ops=%d p50=%.3fms p95=%.3fms p99=%.3fms%n",
                result.ops(), result.p50Ms(), result.p95Ms(), result.p99Ms());
        assertThat(result.p99Ms()).isLessThan(10);
    }

    @Test
    void asyncRaftLatency64Writers() throws Exception {
        RaftBenchmark result = runAsyncRaftLatency(64, 2_000);
        printf("PHASE15-BENCH ASYNC-RAFT-LATENCY writers=64 ops=%d p50=%.3fms p95=%.3fms p99=%.3fms%n",
                result.ops(), result.p50Ms(), result.p95Ms(), result.p99Ms());
        assertThat(result.p99Ms()).isLessThan(10);
    }

    @Test
    void asyncRaftLatency256Writers() throws Exception {
        RaftBenchmark result = runAsyncRaftLatency(256, 1_000);
        printf("PHASE15-BENCH ASYNC-RAFT-LATENCY writers=256 ops=%d p50=%.3fms p95=%.3fms p99=%.3fms%n",
                result.ops(), result.p50Ms(), result.p95Ms(), result.p99Ms());
        // 任务对 1/64 写者明确 P99<10ms；256 写者仅报告（P99≈10ms，接近目标）
        assertThat(result.p99Ms()).isLessThan(20);
    }

    @Test
    void chaosRecoveryTime() throws Exception {
        int rounds = 3;
        long[] electionMs = new long[rounds];
        long[] probeMs = new long[rounds];
        long[] catchupMs = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            Fixture fixture = cluster();
            try {
                ClusterNode oldLeader = awaitLeader(fixture);
                oldLeader.put(bytes("before"), bytes("v"));
                long t0 = System.nanoTime();
                oldLeader.raft().suspend();
                oldLeader.raft().close();
                ClusterNode newLeader = awaitLeader(fixture, oldLeader.id(), 5000);
                long t1 = System.nanoTime();
                newLeader.put(bytes("probe"), bytes("v"));
                long t2 = System.nanoTime();
                awaitAllSee(fixture, bytes("probe"), 5000);
                long t3 = System.nanoTime();
                electionMs[r] = (t1 - t0) / 1_000_000;
                probeMs[r] = (t2 - t1) / 1_000_000;
                catchupMs[r] = (t3 - t2) / 1_000_000;
                assertThat(newLeader.get(bytes("before"))).isNotNull();
            } finally {
                fixture.close();
            }
        }
        Arrays.sort(electionMs);
        Arrays.sort(probeMs);
        Arrays.sort(catchupMs);
        printf("PHASE15-BENCH CHAOS-RECOVERY rounds=%d election_min=%.0fms election_p50=%.0fms election_max=%.0fms probe_p50=%.0fms catchup_p50=%.0fms%n",
                rounds, (double) electionMs[0], (double) electionMs[1],
                (double) electionMs[rounds - 1], (double) probeMs[1],
                (double) catchupMs[1]);
        assertThat(electionMs[rounds - 1]).isLessThan(5000);
    }

    @Test
    void tlsRotationLatency() throws Exception {
        SelfSignedCertificate certA = new SelfSignedCertificate("localhost");
        SelfSignedCertificate certB = new SelfSignedCertificate("localhost");
        CertificateManager manager = CertificateManager.load(
                certA.certificate().toPath(), certA.privateKey().toPath(), null);
        int rounds = 40;
        long[] latenciesUs = new long[rounds];
        for (int i = 0; i < rounds; i++) {
            SelfSignedCertificate cert = i % 2 == 0 ? certA : certB;
            long t0 = System.nanoTime();
            manager.rotate(cert.certificate().toPath(), cert.privateKey().toPath(), null);
            latenciesUs[i] = (System.nanoTime() - t0) / 1_000;
        }
        Arrays.sort(latenciesUs);
        printf("PHASE15-BENCH TLS-ROTATION rounds=%d min=%.3fms p50=%.3fms p99=%.3fms max=%.3fms%n",
                rounds, latenciesUs[0] / 1000.0,
                latenciesUs[rounds / 2] / 1000.0,
                latenciesUs[rounds - 1] / 1000.0,
                latenciesUs[rounds - 1] / 1000.0);
        assertThat(manager.serverContext()).isNotNull();
        // 轮换为低频运维操作（SslContext 重建 + 原子切换），无硬性目标；
        // 上限 100ms 仅防回归
        assertThat(latenciesUs[rounds - 1]).isLessThan(100_000);
    }

    // ---------- helpers ----------

    private void runMigrationBenchmark(int valueSize, int count) throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            byte[] value = new byte[valueSize];
            Arrays.fill(value, (byte) 'v');
            for (int i = 0; i < count; i++) {
                source.put(key(i), value);
            }
            int batchSize = io.tieringkv.cluster.migration.streaming.BatchEncoder
                    .batchSizeFor(valueSize + 16);
            StreamingMigrator migrator = new StreamingMigrator(source, target,
                    new io.tieringkv.cluster.sharding.SlotTable(), dir,
                    0, HashSlotRouter.SLOT_COUNT - 1, 1, Long.MAX_VALUE);
            long start = System.nanoTime();
            while (!migrator.runBatch(batchSize)) {
                // stream
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            long totalBytes = (long) count * (16 + valueSize);
            double mbPerSec = totalBytes / 1024.0 / 1024.0 / seconds;
            printf("PHASE15-BENCH STREAMING-MIGRATION value=%dB entries=%d bytes=%dMB time=%.3fs MB/s=%.1f%n",
                    valueSize, count, totalBytes / 1024 / 1024, seconds, mbPerSec);
            assertThat(target.size()).isEqualTo(count);
            assertThat(mbPerSec).isGreaterThan(10); // 回归下限，目标值见报告
        } finally {
            source.close();
            target.close();
        }
    }

    private static RaftBenchmark runAsyncRaftThroughput(int writers, int opsPerWriter)
            throws Exception {
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        RaftNode[] nodes = RaftTestSupport.group3(applied);
        RaftTestSupport.startAll(nodes);
        RaftNode leader = RaftTestSupport.awaitLeader(List.of(nodes), 5000);
        AtomicReference<RaftNode> leaderRef = new AtomicReference<>(leader);
        AsyncProposalQueue queue = new AsyncProposalQueue(1_000_000);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, leaderRef::get);
        int totalOps = writers * opsPerWriter;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalOps);
        AtomicInteger errors = new AtomicInteger();
        long start = System.nanoTime();
        try {
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                pool.submit(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < opsPerWriter; i++) {
                        byte[] command = ("cmd:" + writer + ":" + i)
                                .getBytes(StandardCharsets.UTF_8);
                        client.submit(command, (index, error) -> {
                            if (error != null) {
                                errors.incrementAndGet();
                            }
                            doneLatch.countDown();
                        });
                    }
                });
            }
            start = System.nanoTime();
            startGate.countDown();
            assertThat(doneLatch.await(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            client.close();
            RaftTestSupport.closeAll(nodes);
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        assertThat(errors.get()).isZero();
        return new RaftBenchmark(totalOps, totalOps / seconds, 0, 0, 0);
    }

    private static RaftBenchmark runAsyncRaftLatency(int writers, int opsPerWriter)
            throws Exception {
        int warmup = Math.max(200, opsPerWriter / 10);
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        RaftNode[] nodes = RaftTestSupport.group3(applied);
        RaftTestSupport.startAll(nodes);
        RaftNode leader = RaftTestSupport.awaitLeader(List.of(nodes), 5000);
        AtomicReference<RaftNode> leaderRef = new AtomicReference<>(leader);
        AsyncProposalQueue queue = new AsyncProposalQueue(1_000_000);
        AsyncReplicationClient client = new AsyncReplicationClient(queue, leaderRef::get);
        int totalOps = writers * opsPerWriter;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        long[][] writerLatencies = new long[writers][opsPerWriter];
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch doneLatch = new CountDownLatch(writers);
        long start = System.nanoTime();
        try {
            for (int i = 0; i < warmup; i++) {
                BlockingQueue<OpResult> slot = new ArrayBlockingQueue<>(1);
                client.submit(bytes("warmup:" + i),
                        (index, error) -> slot.offer(new OpResult(index, error, 0)));
                OpResult result = slot.take();
                if (result.error() != null) {
                    errors.incrementAndGet();
                }
            }
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                pool.submit(() -> {
                    try {
                        startGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    BlockingQueue<OpResult> slot = new ArrayBlockingQueue<>(1);
                    for (int i = 0; i < opsPerWriter; i++) {
                        byte[] command = ("cmd:" + writer + ":" + i)
                                .getBytes(StandardCharsets.UTF_8);
                        long t0 = System.nanoTime();
                        client.submit(command, (index, error) -> {
                            long elapsed = System.nanoTime() - t0;
                            slot.offer(new OpResult(index, error, elapsed));
                        });
                        try {
                            OpResult result = slot.take();
                            if (result.error() != null) {
                                errors.incrementAndGet();
                            } else {
                                writerLatencies[writer][i] = result.elapsedNanos();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    doneLatch.countDown();
                });
            }
            start = System.nanoTime();
            startGate.countDown();
            assertThat(doneLatch.await(90, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
            client.close();
            RaftTestSupport.closeAll(nodes);
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        assertThat(errors.get()).isZero();
        long[] sorted = new long[totalOps];
        int pos = 0;
        for (long[] writer : writerLatencies) {
            System.arraycopy(writer, 0, sorted, pos, writer.length);
            pos += writer.length;
        }
        Arrays.sort(sorted);
        return new RaftBenchmark(totalOps, 0,
                sorted[(int) (sorted.length * 0.50)] / 1_000_000.0,
                sorted[(int) (sorted.length * 0.95)] / 1_000_000.0,
                sorted[(int) (sorted.length * 0.99)] / 1_000_000.0);
    }

    private record OpResult(Long index, Throwable error, long elapsedNanos) {
    }

    private static Fixture cluster() throws Exception {
        MetadataServer metadata = new MetadataServer();
        metadata.join("n1", 1);
        metadata.join("n2", 1);
        metadata.join("n3", 1);
        metadata.createShard(new ShardId(0), List.of("n1", "n2", "n3"), null);
        LeaderElection election = new LeaderElection(100, 80);
        List<RaftNode> peers = new ArrayList<>();
        ClusterNode n1 = ClusterNode.create("n1", peers, MemTable.create(), election, 25, 10);
        ClusterNode n2 = ClusterNode.create("n2", peers, MemTable.create(), election, 25, 10);
        ClusterNode n3 = ClusterNode.create("n3", peers, MemTable.create(), election, 25, 10);
        peers.add(n1.raft());
        peers.add(n2.raft());
        peers.add(n3.raft());
        n1.start();
        n2.start();
        n3.start();
        Map<String, ClusterNode> nodes = new HashMap<>();
        nodes.put("n1", n1);
        nodes.put("n2", n2);
        nodes.put("n3", n3);
        ClusterNode leader = awaitLeader(new Fixture(metadata, nodes));
        metadata.updateLeader(0, leader.id());
        return new Fixture(metadata, nodes);
    }

    private static ClusterNode awaitLeader(Fixture fixture) throws InterruptedException {
        return awaitLeader(fixture, null, 5000);
    }

    private static ClusterNode awaitLeader(Fixture fixture, String excludeId,
                                           long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterNode node : fixture.nodes().values()) {
                if (node.id().equals(excludeId)) {
                    continue;
                }
                if (node.raft().state() == RaftState.LEADER
                        && node.id().equals(node.raft().leaderId())) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no leader within " + timeoutMillis + "ms");
    }

    private static void awaitAllSee(Fixture fixture, byte[] key, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (ClusterNode node : fixture.nodes().values()) {
                if (node.raft().active() && node.get(key) == null) {
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

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "mig:%08d", i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record RaftBenchmark(int ops, double opsPerSecond,
                                 double p50Ms, double p95Ms, double p99Ms) {
    }

    private record Fixture(MetadataServer metadata, Map<String, ClusterNode> nodes)
            implements AutoCloseable {

        @Override
        public void close() {
            for (ClusterNode node : nodes.values()) {
                node.close();
            }
        }
    }
}
