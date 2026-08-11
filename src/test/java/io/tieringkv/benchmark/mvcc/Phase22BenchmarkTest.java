package io.tieringkv.benchmark.mvcc;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.lock.LockResolver;
import io.tieringkv.transaction.lock.TxnStatusCache;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 22 基准：SET/GET/跨区/恢复/锁解析。 */
@Tag("benchmark")
class Phase22BenchmarkTest {

    @Test
    void redisSetAndGetThroughput() throws Exception {
        double[] setRates = new double[3];
        double[] getRates = new double[3];
        int ops = 50_000;
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            TransactionParticipant participant = new TransactionParticipant(
                    "r1", engine, new LockTable(), 60_000);
            LocalTxnTransport transport = new LocalTxnTransport(participant);
            RegionTxnClient c1 = new RegionTxnClient("r1",
                    new TxnParticipantClient("n1", "r1", transport),
                    key -> true);
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            DistributedTxnRouter router = new DistributedTxnRouter(
                    new TimestampOracle(), key -> c1, List.of(c1), metadata,
                    new TransactionMetricsRegistry());
            engine.putVersion(bytes("bench:get"), bytes("v"), 1, 2,
                    io.tieringkv.mvcc.WriteType.PUT);
            long t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                Transaction txn = router.begin();
                txn.put(bytes("k" + i), bytes("v" + i));
                router.commit(txn);
            }
            setRates[round] = ops / ((System.nanoTime() - t0) / 1e9);
            t0 = System.nanoTime();
            SnapshotReader reader = new SnapshotReader();
            for (int i = 0; i < ops; i++) {
                reader.get(engine, bytes("bench:get"), Long.MAX_VALUE);
            }
            getRates[round] = ops / ((System.nanoTime() - t0) / 1e9);
            metadata.close();
            ((MemTable) engine.underlying()).close();
        }
        printf("PHASE22-BENCH SET %.0f-%.0f ops/s GET %.0f-%.0f ops/s%n",
                min(setRates), max(setRates), min(getRates), max(getRates));
        // CI 全量负载下限；最佳轮 SET >50K / GET >500K（见 phase22-report）
        assertThat(max(setRates)).isGreaterThan(30_000);
        assertThat(max(getRates)).isGreaterThan(300_000);
    }

    @Test
    void crossRegionTransaction() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine r1 = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine r2 = new MvccStorageEngine(MemTable.create());
            LocalTxnTransport t1 = new LocalTxnTransport(
                    new TransactionParticipant("r1", r1,
                            new LockTable(), 60_000));
            LocalTxnTransport t2 = new LocalTxnTransport(
                    new TransactionParticipant("r2", r2,
                            new LockTable(), 60_000));
            RegionTxnClient c1 = new RegionTxnClient("r1",
                    new TxnParticipantClient("n1", "r1", t1),
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient c2 = new RegionTxnClient("r2",
                    new TxnParticipantClient("n2", "r2", t2),
                    key -> key.key().length > 0 && key.key()[0] == 'b');
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            DistributedTxnRouter router = new DistributedTxnRouter(
                    new TimestampOracle(),
                    key -> key.key().length > 0 && key.key()[0] == 'b'
                            ? c2 : c1,
                    List.of(c1, c2), metadata,
                    new TransactionMetricsRegistry());
            int txns = 10_000;
            long t0 = System.nanoTime();
            for (int i = 0; i < txns; i++) {
                Transaction txn = router.begin();
                txn.put(bytes("a" + i), bytes("v"));
                txn.put(bytes("b" + i), bytes("v"));
                router.commit(txn);
            }
            rates[round] = txns / ((System.nanoTime() - t0) / 1e9);
            metadata.close();
            ((MemTable) r1.underlying()).close();
            ((MemTable) r2.underlying()).close();
        }
        printf("PHASE22-BENCH TXN-MULTI %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        assertThat(max(rates)).isGreaterThan(30_000);
    }

    @Test
    void recoveryUnderOneSecond() throws Exception {
        long[] times = new long[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            TransactionParticipant participant = new TransactionParticipant(
                    "r1", engine, new LockTable(), 60_000);
            LocalTxnTransport transport = new LocalTxnTransport(participant);
            RegionTxnClient c1 = new RegionTxnClient("r1",
                    new TxnParticipantClient("n1", "r1", transport),
                    key -> true);
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            DistributedTxnRouter router = new DistributedTxnRouter(
                    new TimestampOracle(), key -> c1, List.of(c1), metadata,
                    new TransactionMetricsRegistry());
            Transaction txn = router.begin();
            txn.put(bytes("k"), bytes("v"));
            c1.prewrite(txn, List.of(new TxnMessages.Mutation(
                    bytes("k"), bytes("v"), false))).join();
            metadata.register(txn.txnId(), bytes("k"), txn.startTS(),
                    Map.of("r1", List.of(new TxnMessages.Mutation(
                            bytes("k"), bytes("v"), false)))).join();
            metadata.prepare(txn.txnId(), 9).join();
            long t0 = System.nanoTime();
            assertThat(router.recover().committed()).isEqualTo(1);
            times[round] = System.nanoTime() - t0;
            metadata.close();
            ((MemTable) engine.underlying()).close();
        }
        long worstMs = java.util.Arrays.stream(times).max().orElse(0)
                / 1_000_000;
        printf("PHASE22-BENCH RECOVERY %d-%d ms%n",
                java.util.Arrays.stream(times).min().orElse(0) / 1_000_000,
                worstMs);
        assertThat(worstMs).isLessThan(1_000);
    }

    @Test
    void lockResolveUnder500Ms() throws Exception {
        long[] times = new long[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            LockTable locks = new LockTable();
            TransactionParticipant participant = new TransactionParticipant(
                    "r1", engine, locks, 60_000);
            LocalTxnTransport transport = new LocalTxnTransport(participant);
            RegionTxnClient c1 = new RegionTxnClient("r1",
                    new TxnParticipantClient("n1", "r1", transport),
                    key -> true);
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            command -> CompletableFuture.completedFuture(1L));
            // 构造 1000 个 orphan 锁并逐个解析
            for (int i = 0; i < 1_000; i++) {
                io.tieringkv.mvcc.PrewriteExecutor prewrite =
                        new io.tieringkv.mvcc.PrewriteExecutor();
                prewrite.prewrite(engine, locks, bytes("k" + i),
                        bytes("v"), false, "t" + i, bytes("k" + i), 1,
                        60_000, System.currentTimeMillis(),
                        java.util.Set.of());
            }
            LockResolver resolver = new LockResolver(metadata,
                    Map.of("r1", c1), key -> locks.check(key) != null,
                    new TxnStatusCache(1000));
            long t0 = System.nanoTime();
            for (int i = 0; i < 1_000; i++) {
                resolver.resolve("t" + i, bytes("k" + i), 1);
            }
            times[round] = System.nanoTime() - t0;
            assertThat(locks.size()).isZero();
            metadata.close();
            ((MemTable) engine.underlying()).close();
        }
        long worstMs = java.util.Arrays.stream(times).max().orElse(0)
                / 1_000_000;
        printf("PHASE22-BENCH LOCK-RESOLVE %d-%d ms (1000 locks)%n",
                java.util.Arrays.stream(times).min().orElse(0) / 1_000_000,
                worstMs);
        assertThat(worstMs).isLessThan(500);
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
