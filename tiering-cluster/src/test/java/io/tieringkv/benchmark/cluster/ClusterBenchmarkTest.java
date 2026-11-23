package io.tieringkv.benchmark.cluster;

import io.tieringkv.cluster.ClusterClient;
import io.tieringkv.cluster.ClusterNode;
import io.tieringkv.cluster.metadata.MetadataServer;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.ShardId;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式基准（Phase 11）：单分片写/读吞吐与延迟、多分片分布与路由开销、
 * Raft 复制写延迟与复制滞后、leader 选举时间（目标 &lt; 5s）。
 * 进程内原型：网络往返不包含在指标中。
 */
@Tag("benchmark")
class ClusterBenchmarkTest {

    private static final int OPS = 20_000;
    private static final int READ_OPS = 100_000;

    @Test
    void singleShardThroughputAndLatency() throws Exception {
        try (Fixture fixture = cluster(1)) {
            // 写路径：Client → slot 路由 → leader → Raft 复制 → 本地 apply
            long[] writeLatencyUs = new long[OPS];
            long start = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                long t0 = System.nanoTime();
                fixture.client().put(key(i), value(8));
                writeLatencyUs[i] = (System.nanoTime() - t0) / 1_000;
            }
            double writeSeconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Arrays.sort(writeLatencyUs);
            long[] wp = percentiles(writeLatencyUs);
            printf("CLUSTER-BENCH SINGLE-SHARD WRITE ops=%d ops/s=%.0f p50=%.3fms p95=%.3fms p99=%.3fms%n",
                    OPS, OPS / writeSeconds, wp[0] / 1000.0, wp[1] / 1000.0, wp[2] / 1000.0);

            // 读路径：Client → 路由 → leader 本地引擎（无 Raft 往返）
            long[] readLatencyUs = new long[READ_OPS];
            long readStart = System.nanoTime();
            for (int i = 0; i < READ_OPS; i++) {
                long t0 = System.nanoTime();
                assertThat(fixture.client().get(key(i % OPS))).isNotNull();
                readLatencyUs[i] = (System.nanoTime() - t0) / 1_000;
            }
            double readSeconds = (System.nanoTime() - readStart) / 1_000_000_000.0;
            Arrays.sort(readLatencyUs);
            long[] rp = percentiles(readLatencyUs);
            printf("CLUSTER-BENCH SINGLE-SHARD READ ops=%d ops/s=%.0f p50=%.3fus p95=%.3fus p99=%.3fus%n",
                    READ_OPS, READ_OPS / readSeconds, (double) rp[0], (double) rp[1], (double) rp[2]);

            assertThat(writeSeconds).isLessThan(60);
            assertThat(readSeconds).isLessThan(30);
        }
    }

    @Test
    void multiShardDistributionAndRoutingOverhead() throws Exception {
        try (Fixture fixture = cluster(3)) {
            int[] counts = new int[3];
            long routeStart = System.nanoTime();
            for (int i = 0; i < 100_000; i++) {
                counts[fixture.metadata().topology().shardFor(key(i))]++;
            }
            double routeSeconds = (System.nanoTime() - routeStart) / 1_000_000_000.0;

            long slotStart = System.nanoTime();
            int slotSum = 0;
            for (int i = 0; i < 1_000_000; i++) {
                slotSum += HashSlotRouter.slot(key(i));
            }
            long slotNanos = System.nanoTime() - slotStart;

            long topoStart = System.nanoTime();
            int leaderSum = 0;
            for (int i = 0; i < 1_000_000; i++) {
                leaderSum += fixture.metadata().topology().leaderFor(key(i)).length();
            }
            long topoNanos = System.nanoTime() - topoStart;

            printf("CLUSTER-BENCH DISTRIBUTION keys=100000 counts=%s min=%.1f%% max=%.1f%%%n",
                    Arrays.toString(counts),
                    min(counts) / 1000.0, max(counts) / 1000.0);
            printf("CLUSTER-BENCH ROUTING slot=%.0fns/op topology=%.0fns/op overhead=%.0fns/op slotSum=%d leaderSum=%d%n",
                    slotNanos / 1_000_000.0, topoNanos / 1_000_000.0,
                    (topoNanos - slotNanos) / 1_000_000.0, slotSum, leaderSum);
            printf("CLUSTER-BENCH MULTI-SHARD RESOLVE ops=100000 ops/s=%.0f%n", 100_000 / routeSeconds);

            for (int count : counts) {
                assertThat(count).isGreaterThan(15_000).isLessThan(45_000);
            }
        }
    }

    @Test
    void replicationWriteLatencyAndCatchUpDelay() throws Exception {
        try (Fixture fixture = cluster(1)) {
            ClusterNode leader = fixture.leader();
            long[] latencyUs = new long[10_000];
            long start = System.nanoTime();
            for (int i = 0; i < latencyUs.length; i++) {
                long t0 = System.nanoTime();
                leader.put(key(i), value(8));
                latencyUs[i] = (System.nanoTime() - t0) / 1_000;
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            Arrays.sort(latencyUs);
            long[] p = percentiles(latencyUs);
            printf("CLUSTER-BENCH REPLICATION WRITE ops=%d ops/s=%.0f p50=%.3fms p95=%.3fms p99=%.3fms%n",
                    latencyUs.length, latencyUs.length / seconds, p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0);

            // 复制滞后：leader 提交后，直到全部 replica 本地可见
            int samples = 20;
            long[] delaysMs = new long[samples];
            for (int s = 0; s < samples; s++) {
                byte[] k = key(50_000 + s);
                leader.put(k, value(8));
                long commitNanos = System.nanoTime();
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline
                        && !allNodesSee(fixture, k)) {
                    Thread.sleep(1);
                }
                assertThat(allNodesSee(fixture, k)).as("replica catch-up").isTrue();
                delaysMs[s] = (System.nanoTime() - commitNanos) / 1_000_000;
            }
            Arrays.sort(delaysMs);
            long[] dp = percentiles(delaysMs);
            printf("CLUSTER-BENCH REPLICATION CATCH-UP samples=%d p50=%.1fms p95=%.1fms p99=%.1fms max=%.1fms%n",
                    samples, (double) dp[0], (double) dp[1], (double) dp[2],
                    (double) delaysMs[samples - 1]);
            assertThat(delaysMs[samples - 1]).isLessThan(1000);
        }
    }

    @Test
    void leaderElectionTimeUnderFiveSeconds() throws Exception {
        int rounds = 5;
        long[] electionMs = new long[rounds];
        for (int r = 0; r < rounds; r++) {
            try (Fixture fixture = cluster(1)) {
                ClusterNode oldLeader = fixture.leader();
                long start = System.currentTimeMillis();
                oldLeader.raft().suspend();
                oldLeader.raft().close();
                ClusterNode newLeader = awaitLeader(fixture.nodes(), 5000);
                electionMs[r] = System.currentTimeMillis() - start;
                assertThat(newLeader.id()).isNotEqualTo(oldLeader.id());
            }
        }
        Arrays.sort(electionMs);
        long[] p = percentiles(electionMs);
        printf("CLUSTER-BENCH ELECTION rounds=%d min=%.0fms p50=%.0fms max=%.0fms%n",
                rounds, (double) electionMs[0], (double) p[1], (double) electionMs[rounds - 1]);
        assertThat(electionMs[rounds - 1]).isLessThan(5000);
    }

    // ---------- helpers ----------

    private static Fixture cluster(int shardCount) throws Exception {
        MetadataServer metadata = new MetadataServer();
        metadata.join("n1", shardCount);
        metadata.join("n2", shardCount);
        metadata.join("n3", shardCount);
        for (int s = 0; s < shardCount; s++) {
            metadata.createShard(new ShardId(s), List.of("n1", "n2", "n3"), null);
        }

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
        ClusterNode leader = awaitLeader(nodes, 5000);
        for (int s = 0; s < shardCount; s++) {
            metadata.updateLeader(s, leader.id());
        }
        ClusterClient client = new ClusterClient(metadata, nodes);
        return new Fixture(metadata, nodes, client, leader);
    }

    private static ClusterNode awaitLeader(Map<String, ClusterNode> nodes, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterNode node : nodes.values()) {
                if (node.raft().state() == RaftState.LEADER) {
                    return node;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no cluster leader elected");
    }

    private static boolean allNodesSee(Fixture fixture, byte[] key) {
        for (ClusterNode node : fixture.nodes().values()) {
            if (node.get(key) == null) {
                return false;
            }
        }
        return true;
    }

    private static byte[] key(int i) {
        return ("cluster:bench:" + i).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int size) {
        byte[] bytes = new byte[size];
        Arrays.fill(bytes, (byte) 'v');
        return bytes;
    }

    private static long[] percentiles(long[] sorted) {
        return new long[]{
                sorted[(int) (sorted.length * 0.50)],
                sorted[(int) (sorted.length * 0.95)],
                sorted[(int) (sorted.length * 0.99)]
        };
    }

    private static double min(int[] values) {
        int min = Integer.MAX_VALUE;
        for (int value : values) {
            min = Math.min(min, value);
        }
        return min;
    }

    private static double max(int[] values) {
        int max = 0;
        for (int value : values) {
            max = Math.max(max, value);
        }
        return max;
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record Fixture(
            MetadataServer metadata,
            Map<String, ClusterNode> nodes,
            ClusterClient client,
            ClusterNode leader) implements AutoCloseable {

        @Override
        public void close() {
            for (ClusterNode node : nodes.values()) {
                node.close();
            }
        }
    }
}
