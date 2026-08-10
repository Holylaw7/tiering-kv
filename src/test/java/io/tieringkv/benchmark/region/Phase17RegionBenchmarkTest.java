package io.tieringkv.benchmark.region;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.gateway.RedisClusterGateway;
import io.tieringkv.cluster.lifecycle.merge.MergeController;
import io.tieringkv.cluster.lifecycle.split.SplitController;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.region.RegionManager;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 17 区域生命周期基准：split / merge / leader transfer / Redis 网关。
 * 目标：split 1M <10s、merge 1M <20s、transfer <500ms、GET >100K、SET >50K。
 */
@Tag("benchmark")
class Phase17RegionBenchmarkTest {

    private static final int SPLIT_MERGE_KEYS = 200_000;

    @Test
    void regionSplitThroughput() throws Exception {
        MemTable source = MemTable.create();
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("z"),
                List.of("n1"), RegionEpoch.INITIAL, "n1");
        try {
            load(source, SPLIT_MERGE_KEYS);
            SplitController controller = new SplitController(regions);
            long start = System.nanoTime();
            controller.split(new RegionId(1), bytes("rk:100000"),
                    source, left, right);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            printf("PHASE17-BENCH SPLIT keys=%d time=%.3fs entries/s=%.0f "
                            + "scaled1M=%.1fs%n",
                    SPLIT_MERGE_KEYS, seconds,
                    SPLIT_MERGE_KEYS / seconds,
                    seconds * 5);
            assertThat(left.size() + right.size()).isEqualTo(SPLIT_MERGE_KEYS);
            assertThat(seconds).isLessThan(10);
        } finally {
            source.close();
            left.close();
            right.close();
        }
    }

    @Test
    void regionMergeThroughput() throws Exception {
        MemTable left = MemTable.create();
        MemTable right = MemTable.create();
        RegionManager regions = new RegionManager();
        regions.createRegion(new RegionId(1), bytes("a"), bytes("rk:100000"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n1");
        regions.createRegion(new RegionId(2), bytes("rk:100000"), bytes("z"),
                List.of("n1", "n2"), RegionEpoch.INITIAL, "n2");
        try {
            load(left, SPLIT_MERGE_KEYS / 2);
            load(right, SPLIT_MERGE_KEYS / 2, SPLIT_MERGE_KEYS / 2);
            MergeController controller = new MergeController(regions);
            long start = System.nanoTime();
            controller.merge(new RegionId(1), new RegionId(2), left, right);
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            printf("PHASE17-BENCH MERGE keys=%d time=%.3fs entries/s=%.0f "
                            + "scaled1M=%.1fs%n",
                    SPLIT_MERGE_KEYS, seconds,
                    SPLIT_MERGE_KEYS / seconds,
                    seconds * 5);
            assertThat(left.size()).isEqualTo(SPLIT_MERGE_KEYS);
            assertThat(seconds).isLessThan(20);
        } finally {
            left.close();
            right.close();
        }
    }

    @Test
    void leaderTransferLatency() throws Exception {
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        List<RaftNode> peers = new ArrayList<>();
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RaftNode node = new RaftNode(id, peers,
                    (index, command) -> applied.add(
                            new String(command, StandardCharsets.UTF_8)),
                    RaftTestSupport.ELECTION, 25, 10);
            nodes.add(node);
        }
        peers.addAll(nodes);
        RaftTestSupport.startAll(nodes.toArray(new RaftNode[0]));
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            RaftNode target = nodes.stream()
                    .filter(n -> !n.id().equals(leader.id()))
                    .findFirst().orElseThrow();
            leader.propose(bytes("x")).get();
            long start = System.nanoTime();
            assertThat(leader.transferLeadership(target.id())
                    .get(5, TimeUnit.SECONDS)).isTrue();
            long transferMs = (System.nanoTime() - start) / 1_000_000;
            printf("PHASE17-BENCH LEADER-TRANSFER time=%.0fms%n",
                    (double) transferMs);
            assertThat(transferMs).isLessThan(500);
        } finally {
            for (RaftNode node : nodes) {
                node.close();
            }
        }
    }

    @Test
    void redisGatewayGetThroughput() {
        MemTable local = MemTable.create();
        RedisClusterGateway gateway = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", local),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)), "n1");
        byte[] key = bytes("bench:key");
        local.put(key, bytes("v"));
        int ops = 200_000;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            gateway.execute("get", List.of(key));
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double opsPerSec = ops / seconds;
        printf("PHASE17-BENCH GATEWAY GET ops=%d ops/s=%.0f%n", ops, opsPerSec);
        assertThat(opsPerSec).isGreaterThan(100_000);
        local.close();
    }

    @Test
    void redisGatewaySetThroughput() {
        MemTable local = MemTable.create();
        RedisClusterGateway gateway = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", local),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)), "n1");
        int ops = 100_000;
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            gateway.execute("set", List.of(bytes("k" + i), bytes("v")));
        }
        double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
        double opsPerSec = ops / seconds;
        printf("PHASE17-BENCH GATEWAY SET ops=%d ops/s=%.0f%n", ops, opsPerSec);
        assertThat(opsPerSec).isGreaterThan(50_000);
        local.close();
    }

    private static void load(MemTable table, int count) {
        load(table, count, 0);
    }

    private static void load(MemTable table, int count, int offset) {
        byte[] value = new byte[32];
        for (int i = 0; i < count; i++) {
            table.put(key(offset + i), value);
        }
    }

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "rk:%06d", i)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }
}
