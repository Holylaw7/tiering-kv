package io.tieringkv.benchmark.mvcc;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 24 最终生产基准（SLA）。 */
@Tag("benchmark")
class Phase24BenchmarkTest {

    @Test
    void gatewaySetThroughput() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            RegionTxnClient region = region(engine);
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            DistributedTxnRouter router = new DistributedTxnRouter(
                    new TimestampOracle(), key -> region, List.of(region),
                    metadata, new TransactionMetricsRegistry());
            int ops = 50_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                Transaction txn = router.begin();
                txn.put(bytes("k" + i), bytes("v"));
                router.commit(txn);
            }
            rates[round] = ops / ((System.nanoTime() - t0) / 1e9);
            metadata.close();
            ((MemTable) engine.underlying()).close();
        }
        printf("PHASE24-BENCH SET %.0f-%.0f ops/s%n", min(rates), max(rates));
        assertThat(max(rates)).isGreaterThan(50_000);
    }

    @Test
    void crossRegionThroughput() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine a = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine b = new MvccStorageEngine(MemTable.create());
            RegionTxnClient ca = region(a, 'a');
            RegionTxnClient cb = region(b, 'b');
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            DistributedTxnRouter router = new DistributedTxnRouter(
                    new TimestampOracle(),
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? cb : ca,
                    List.of(ca, cb), metadata,
                    new TransactionMetricsRegistry());
            int ops = 10_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                Transaction txn = router.begin();
                txn.put(bytes("a" + i), bytes("v"));
                txn.put(bytes("b" + i), bytes("v"));
                router.commit(txn);
            }
            rates[round] = ops / ((System.nanoTime() - t0) / 1e9);
            metadata.close();
            ((MemTable) a.underlying()).close();
            ((MemTable) b.underlying()).close();
        }
        printf("PHASE24-BENCH TXN-MULTI %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        assertThat(max(rates)).isGreaterThan(30_000);
    }

    @Test
    void recoveryUnderOneSecond() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        RegionTxnClient region = region(engine);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> region, List.of(region),
                metadata, new TransactionMetricsRegistry());
        Transaction txn = router.begin();
        txn.put(bytes("k"), bytes("v"));
        region.prewrite(txn, List.of(new TxnMessages.Mutation(
                bytes("k"), bytes("v"), false))).join();
        metadata.register(txn.txnId(), bytes("k"), txn.startTS(),
                java.util.Map.of("r1", List.of(new TxnMessages.Mutation(
                        bytes("k"), bytes("v"), false)))).join();
        metadata.prepare(txn.txnId(), 9).join();
        long t0 = System.nanoTime();
        assertThat(router.recover().committed()).isEqualTo(1);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        printf("PHASE24-BENCH RECOVERY %d ms%n", ms);
        assertThat(ms).isLessThan(1_000);
        metadata.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void leaderFailoverUnder500Ms() throws Exception {
        long[] times = new long[3];
        for (int round = 0; round < 3; round++) {
            java.util.List<io.tieringkv.cluster.raft.RaftNode> peers =
                    new java.util.ArrayList<>();
            java.util.List<io.tieringkv.cluster.raft.RaftNode> nodes =
                    new java.util.ArrayList<>();
            for (int i = 0; i < 3; i++) {
                io.tieringkv.cluster.raft.RaftNode node =
                        new io.tieringkv.cluster.raft.RaftNode("n" + i, peers,
                                (index, command) -> {
                                },
                                new io.tieringkv.cluster.raft.LeaderElection(
                                        100, 80), 25, 10);
                nodes.add(node);
                peers.add(node);
            }
            for (io.tieringkv.cluster.raft.RaftNode node : nodes) {
                node.start();
            }
            io.tieringkv.cluster.raft.RaftNode leader = null;
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (io.tieringkv.cluster.raft.RaftNode node : nodes) {
                    if (node.state() == io.tieringkv.cluster.raft.RaftState
                            .LEADER) {
                        leader = node;
                        break;
                    }
                }
                if (leader != null) {
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(leader).isNotNull();
            long t0 = System.nanoTime();
            leader.suspend();
            leader.close();
            deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                boolean elected = nodes.stream().anyMatch(node ->
                        node.state() == io.tieringkv.cluster.raft.RaftState
                                .LEADER);
                if (elected) {
                    break;
                }
                Thread.sleep(10);
            }
            times[round] = System.nanoTime() - t0;
            for (io.tieringkv.cluster.raft.RaftNode node : nodes) {
                node.close();
            }
        }
        long worstMs = java.util.Arrays.stream(times).max().orElse(0)
                / 1_000_000;
        printf("PHASE24-BENCH LEADER-FAILOVER %d-%d ms%n",
                java.util.Arrays.stream(times).min().orElse(0) / 1_000_000,
                worstMs);
        assertThat(worstMs).isLessThan(500);
    }

    @Test
    void lockResolveUnder500Ms() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        RegionTxnClient region = region(engine);
        for (int i = 0; i < 500; i++) {
            io.tieringkv.mvcc.PrewriteExecutor prewrite =
                    new io.tieringkv.mvcc.PrewriteExecutor();
            prewrite.prewrite(engine, new LockTable(), bytes("k" + i),
                    bytes("v"), false, "t" + i, bytes("k" + i), 1, 60_000,
                    System.currentTimeMillis(), java.util.Set.of());
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            region.resolveLock("t" + i, 1, 9, bytes("k" + i),
                    List.of(new TxnMessages.Mutation(bytes("k" + i),
                            bytes("v"), false)), true).join();
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        printf("PHASE24-BENCH LOCK-RESOLVE %d ms (500 locks)%n", ms);
        assertThat(ms).isLessThan(500);
        ((MemTable) engine.underlying()).close();
    }

    private static RegionTxnClient region(MvccStorageEngine engine) {
        return region(engine, 'k');
    }

    private static RegionTxnClient region(MvccStorageEngine engine, char owns) {
        LocalTxnTransport transport = new LocalTxnTransport(
                new TransactionParticipant("r1", engine,
                        new LockTable(), 60_000));
        return new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport),
                key -> key.key().length == 0 || key.key()[0] == owns);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private static double min(double[] values) {
        return java.util.Arrays.stream(values).min().orElse(0);
    }

    private static double max(double[] values) {
        return java.util.Arrays.stream(values).max().orElse(0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
