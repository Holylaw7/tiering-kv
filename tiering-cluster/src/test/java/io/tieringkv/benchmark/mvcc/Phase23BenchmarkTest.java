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

/** Phase 23 基准：SET/跨区/恢复。 */
@Tag("benchmark")
class Phase23BenchmarkTest {

    @Test
    void runtimeSetThroughput() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            RegionTxnClient region = region(engine, "r1", key -> true);
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
        printf("PHASE23-BENCH SET %.0f-%.0f ops/s%n", min(rates), max(rates));
        assertThat(max(rates)).isGreaterThan(50_000);
    }

    @Test
    void crossRegionThroughput() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine a = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine b = new MvccStorageEngine(MemTable.create());
            RegionTxnClient ca = region(a, "r1",
                    key -> key.key().length > 0 && key.key()[0] == 'a');
            RegionTxnClient cb = region(b, "r2",
                    key -> key.key().length > 0 && key.key()[0] == 'b');
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
        printf("PHASE23-BENCH TXN-MULTI %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        assertThat(max(rates)).isGreaterThan(30_000);
    }

    @Test
    void recoveryUnderOneSecond() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        RegionTxnClient region = region(engine, "r1", key -> true);
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
        printf("PHASE23-BENCH RECOVERY %d ms%n", ms);
        assertThat(ms).isLessThan(1_000);
        metadata.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void lockResolveUnder500Ms() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        RegionTxnClient region = region(engine, "r1", key -> true);
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
        printf("PHASE23-BENCH LOCK-RESOLVE %d ms (500 locks)%n", ms);
        assertThat(ms).isLessThan(500);
        ((MemTable) engine.underlying()).close();
    }

    private static RegionTxnClient region(MvccStorageEngine engine,
                                          String id,
                                          java.util.function.Predicate<
                                                  io.tieringkv.mvcc.ByteKey> owns) {
        LocalTxnTransport transport = new LocalTxnTransport(
                new TransactionParticipant(id, engine,
                        new LockTable(), 60_000));
        return new RegionTxnClient(id,
                new TxnParticipantClient("n1", id, transport), owns);
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
