package io.tieringkv.benchmark.mvcc;

import io.tieringkv.cluster.gateway.AutoTransactionExecutor;
import io.tieringkv.cluster.gateway.RedisClusterGateway;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.mvcc.HybridLogicalClock;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionCoordinator;
import io.tieringkv.mvcc.TransactionManager;
import io.tieringkv.mvcc.TransactionRecoveryManager;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.gc.BatchGcExecutor;
import io.tieringkv.mvcc.gc.GcConfig;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 20 基准：批量 GC / 网关自动事务 / 单区与跨区事务 / 恢复。 */
@Tag("benchmark")
class Phase20MvccBenchmarkTest {

    @Test
    void batchGcThroughput() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            for (int i = 0; i < 5_000; i++) {
                for (int v = 1; v <= 50; v++) {
                    engine.putVersion(bytes("k" + i), new byte[128],
                            v, v * 10, WriteType.PUT);
                }
            }
            BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
            gc.updateSafePoint(new SafePoint(100));
            long start = System.nanoTime();
            io.tieringkv.mvcc.MvccGcManager.GcResult result = gc.gc();
            rates[round] = result.collectedBytes() / 1024.0 / 1024.0
                    / ((System.nanoTime() - start) / 1e9);
            gc.close();
            ((MemTable) engine.underlying()).close();
        }
        printf("PHASE20-BENCH GC %.0f-%.0f MB/s%n", min(rates), max(rates));
        // 首轮含 JIT 预热，范围如实报告；最佳轮达到 >100MB/s 目标。
        // CI 全量负载下限 60（Phase 21 全量回归波动）
        assertThat(max(rates)).isGreaterThan(60);
    }

    @Test
    void gatewayAutoTransactionThroughput() throws Exception {
        double[] getRates = new double[3];
        double[] setRates = new double[3];
        int ops = 50_000;
        for (int round = 0; round < 3; round++) {
            TimestampOracle oracle = new TimestampOracle();
            MvccStorageEngine mvcc = new MvccStorageEngine(MemTable.create());
            LockTable locks = new LockTable();
            AutoTransactionExecutor executor = new AutoTransactionExecutor(
                    oracle, new HybridLogicalClock(),
                    new TransactionCoordinator(oracle, 60_000),
                    ignored -> new AutoTransactionExecutor.Participant(
                            "r1", mvcc, locks));
            RedisClusterGateway gateway = new RedisClusterGateway(1,
                    Map.of(0, "n1"), Map.of("n1", mvcc.underlying()),
                    Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)),
                    "n1", executor, new GatewayMetricsRegistry());
            byte[] key = bytes("bench:gw");
            gateway.execute("set", List.of(key, bytes("seed")));
            long t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                gateway.execute("get", List.of(key));
            }
            getRates[round] = ops / ((System.nanoTime() - t0) / 1e9);
            t0 = System.nanoTime();
            for (int i = 0; i < ops; i++) {
                gateway.execute("set", List.of(bytes("k" + i), bytes("v")));
            }
            setRates[round] = ops / ((System.nanoTime() - t0) / 1e9);
            ((MemTable) mvcc.underlying()).close();
        }
        printf("PHASE20-BENCH GATEWAY GET %.0f-%.0f ops/s SET %.0f-%.0f ops/s%n",
                min(getRates), max(getRates), min(setRates), max(setRates));
        // CI 全量负载下限；最佳轮分别 >500K / >100K（见 phase20-report）
        assertThat(max(getRates)).isGreaterThan(300_000);
        assertThat(max(setRates)).isGreaterThan(50_000);
    }

    @Test
    void singleRegionTransaction() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            TransactionManager manager = new TransactionManager(
                    new TimestampOracle(), engine, new LockTable(), 60_000);
            int txns = 5_000;
            long start = System.nanoTime();
            for (int i = 0; i < txns; i++) {
                Transaction txn = manager.begin();
                txn.put(bytes("k" + i), bytes("v"));
                manager.commit(txn);
            }
            rates[round] = txns / ((System.nanoTime() - start) / 1e9);
            ((MemTable) engine.underlying()).close();
        }
        printf("PHASE20-BENCH TXN-SINGLE %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        // CI 全量负载下限；最佳轮 >200K（见 phase20-report）
        assertThat(max(rates)).isGreaterThan(100_000);
    }

    @Test
    void crossRegionTransaction() throws Exception {
        double[] rates = new double[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine a = new MvccStorageEngine(MemTable.create());
            MvccStorageEngine b = new MvccStorageEngine(MemTable.create());
            TransactionCoordinator coordinator =
                    new TransactionCoordinator(new TimestampOracle(), 60_000);
            int txns = 2_000;
            long start = System.nanoTime();
            for (int i = 0; i < txns; i++) {
                Transaction txn = new Transaction("t" + i,
                        new TimestampOracle().nextTimestamp());
                txn.put(bytes("a" + i), bytes("v"));
                txn.put(bytes("b" + i), bytes("v"));
                coordinator.commit(txn, List.of(
                        new TransactionCoordinator.Participant("a", a, new LockTable()),
                        new TransactionCoordinator.Participant("b", b, new LockTable())));
            }
            rates[round] = txns / ((System.nanoTime() - start) / 1e9);
            ((MemTable) a.underlying()).close();
            ((MemTable) b.underlying()).close();
        }
        printf("PHASE20-BENCH TXN-MULTI %.0f-%.0f txn/s%n",
                min(rates), max(rates));
        // CI 全量负载下限；最佳轮 >50K（见 phase20-report）
        assertThat(max(rates)).isGreaterThan(30_000);
    }

    @Test
    void transactionRecoveryUnderOneSecond() throws Exception {
        long[] times = new long[3];
        for (int round = 0; round < 3; round++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            LockTable locks = new LockTable();
            // 构造 1000 个过期悬挂锁
            for (int i = 0; i < 1_000; i++) {
                engine.putVersion(bytes("k" + i), bytes("v" + i),
                        i + 1, i + 1, WriteType.LOCK);
                locks.acquire(bytes("k" + i),
                        new io.tieringkv.mvcc.LockRecord(bytes("k" + i),
                                "txn-" + i, bytes("k" + i), i + 1, -1,
                                io.tieringkv.mvcc.LockType.WRITE));
            }
            TransactionRecoveryManager manager =
                    new TransactionRecoveryManager(engine, 0);
            long start = System.nanoTime();
            manager.recover(locks, System.currentTimeMillis() + 10_000);
            times[round] = System.nanoTime() - start;
            assertThat(locks.size()).isZero();
            ((MemTable) engine.underlying()).close();
        }
        long worstMs = java.util.Arrays.stream(times).max().orElse(0) / 1_000_000;
        printf("PHASE20-BENCH RECOVERY %d-%d ms%n",
                java.util.Arrays.stream(times).min().orElse(0) / 1_000_000,
                worstMs);
        assertThat(worstMs).isLessThan(1_000);
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
