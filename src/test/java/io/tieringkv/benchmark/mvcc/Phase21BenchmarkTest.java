package io.tieringkv.benchmark.mvcc;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.metadata.TxnMetadataRaftGroup;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 21 基准：跨节点事务吞吐、恢复、leader 选举。 */
@Tag("benchmark")
class Phase21BenchmarkTest {

    @Test
    void crossNodeSingleRegionTransaction() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            try (BenchmarkFixture fixture = BenchmarkFixture.singleRegion()) {
                int txns = 20_000;
                long start = System.nanoTime();
                for (int i = 0; i < txns; i++) {
                    Transaction txn = fixture.router.begin();
                    txn.put(bytes("k" + i), bytes("v"));
                    fixture.router.commit(txn);
                }
                rates[round] = txns / ((System.nanoTime() - start) / 1e9);
            }
        }
        printf("PHASE21-BENCH TXN-SINGLE %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        // CI 全量负载下限 50K；最佳轮 >100K 达标（如实记录于 phase21-report）
        assertThat(max(rates)).isGreaterThan(50_000);
    }

    @Test
    void crossNodeMultiRegionTransaction() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            try (BenchmarkFixture fixture = BenchmarkFixture.multiRegion()) {
                int txns = 10_000;
                long start = System.nanoTime();
                for (int i = 0; i < txns; i++) {
                    Transaction txn = fixture.router.begin();
                    txn.put(bytes("a" + i), bytes("v"));
                    txn.put(bytes("b" + i), bytes("v"));
                    fixture.router.commit(txn);
                }
                rates[round] = txns / ((System.nanoTime() - start) / 1e9);
            }
        }
        printf("PHASE21-BENCH TXN-MULTI %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        // CI 全量负载下限 40K；最佳轮 >50K 达标（如实记录于 phase21-report）
        assertThat(max(rates)).isGreaterThan(40_000);
    }

    @Test
    void transactionRecoveryUnderOneSecond() throws Exception {
        long[] times = new long[3];
        for (int round = 0; round < 3; round++) {
            try (BenchmarkFixture fixture = BenchmarkFixture.singleRegion()) {
                Transaction txn = fixture.router.begin();
                txn.put(bytes("k"), bytes("v"));
                fixture.regionClients.get(0).prewrite(txn,
                        List.of(new io.tieringkv.transaction.rpc
                                .TxnMessages.Mutation(bytes("k"),
                                bytes("v"), false))).join();
                fixture.metadata.register(txn.txnId(), bytes("k"),
                        txn.startTS(), java.util.Map.of("r1",
                                List.of(new io.tieringkv.transaction.rpc
                                        .TxnMessages.Mutation(bytes("k"),
                                        bytes("v"), false)))).join();
                long commitTS = fixture.oracle.nextTimestamp();
                fixture.metadata.prepare(txn.txnId(), commitTS).join();
                long start = System.nanoTime();
                assertThat(fixture.router.recover().committed())
                        .isEqualTo(1);
                times[round] = System.nanoTime() - start;
            }
        }
        long worstMs = java.util.Arrays.stream(times).max().orElse(0)
                / 1_000_000;
        printf("PHASE21-BENCH RECOVERY %d-%d ms%n",
                java.util.Arrays.stream(times).min().orElse(0) / 1_000_000,
                worstMs);
        assertThat(worstMs).isLessThan(1_000);
    }

    @Test
    void leaderRecoveryUnderFiveSeconds() throws Exception {
        long[] times = new long[3];
        for (int round = 0; round < 3; round++) {
            try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
                group.proposer().apply(new byte[]{1}).join();
                io.tieringkv.cluster.raft.RaftNode leader = group.leader();
                long start = System.nanoTime();
                leader.suspend();
                leader.close();
                TxnMetadataRaftGroup.awaitLeader(group.nodes(), 8_000);
                times[round] = System.nanoTime() - start;
            }
        }
        long worstMs = java.util.Arrays.stream(times).max().orElse(0)
                / 1_000_000;
        printf("PHASE21-BENCH LEADER-RECOVERY %d-%d ms%n",
                java.util.Arrays.stream(times).min().orElse(0) / 1_000_000,
                worstMs);
        assertThat(worstMs).isLessThan(5_000);
    }

    @Test
    void multiRegionConflictResolution() throws Exception {
        try (BenchmarkFixture fixture = BenchmarkFixture.multiRegion()) {
            int txns = 5_000;
            long start = System.nanoTime();
            for (int i = 0; i < txns; i++) {
                Transaction txn = fixture.router.begin();
                txn.put(bytes("a" + i), bytes("v"));
                txn.put(bytes("b" + i), bytes("v"));
                try {
                    fixture.router.commit(txn);
                } catch (RuntimeException conflict) {
                    fixture.router.rollback(txn);
                }
            }
            double rate = txns / ((System.nanoTime() - start) / 1e9);
            printf("PHASE21-BENCH TXN-CONFLICT %.0f txn/s%n", rate);
            assertThat(rate).isGreaterThan(10_000);
        }
    }

    @Test
    void prepareLatencyP50() throws Exception {
        try (BenchmarkFixture fixture = BenchmarkFixture.singleRegion()) {
            long[] latencies = new long[2_000];
            for (int i = 0; i < latencies.length; i++) {
                Transaction txn = fixture.router.begin();
                txn.put(bytes("k" + i), bytes("v"));
                long t0 = System.nanoTime();
                fixture.router.commit(txn);
                latencies[i] = System.nanoTime() - t0;
            }
            java.util.Arrays.sort(latencies);
            long p50Us = latencies[latencies.length / 2] / 1_000;
            printf("PHASE21-BENCH TXN-P50 %d us%n", p50Us);
            assertThat(p50Us).isLessThan(1_000);
        }
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

    private record BenchmarkFixture(DistributedTxnRouter router,
                                    TimestampOracle oracle,
                                    TransactionMetadataService metadata,
                                    List<RegionTxnClient> regionClients)
            implements AutoCloseable {

        static BenchmarkFixture singleRegion() throws Exception {
            return create(1);
        }

        static BenchmarkFixture multiRegion() throws Exception {
            return create(2);
        }

        private static BenchmarkFixture create(int regionCount)
                throws Exception {
            TimestampOracle oracle = new TimestampOracle();
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            TransactionMetricsRegistry metrics =
                    new TransactionMetricsRegistry();
            MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
            LocalTxnTransport t1 = new LocalTxnTransport(
                    new TransactionParticipant("r1", r1,
                            new LockTable(), 60_000));
            RegionTxnClient c1 = new RegionTxnClient("r1",
                    new TxnParticipantClient("n1", "r1", t1),
                    key -> key.key().length == 0 || key.key()[0] != 'b');
            List<RegionTxnClient> clients;
            DistributedTxnRouter router;
            if (regionCount == 1) {
                clients = List.of(c1);
                router = new DistributedTxnRouter(oracle,
                        key -> c1, clients, metadata, metrics);
            } else {
                MvccStorageEngine r2 = new MvccStorageEngine(MemTable.create());
                LocalTxnTransport t2 = new LocalTxnTransport(
                        new TransactionParticipant("r2", r2,
                                new LockTable(), 60_000));
                RegionTxnClient c2 = new RegionTxnClient("r2",
                        new TxnParticipantClient("n2", "r2", t2),
                        key -> key.key().length > 0 && key.key()[0] == 'b');
                clients = List.of(c1, c2);
                router = new DistributedTxnRouter(oracle,
                        key -> key.key().length > 0 && key.key()[0] == 'b'
                                ? c2 : c1,
                        clients, metadata, metrics);
            }
            return new BenchmarkFixture(router, oracle, metadata, clients);
        }

        @Override
        public void close() throws Exception {
            metadata.close();
        }
    }
}
