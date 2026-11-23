package io.tieringkv.mvcc;

import io.tieringkv.cluster.gateway.AutoTransactionExecutor;
import io.tieringkv.cluster.gateway.RedisClusterGateway;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.mvcc.gc.BatchGcExecutor;
import io.tieringkv.mvcc.gc.GcConfig;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 20 端到端集成：网关自动事务 × 持久化索引 × GC × 日志恢复 × 指标。 */
class Phase20IntegrationTest {

    @TempDir
    Path dir;

    @Test
    void autoTxnWritesSurviveIndexRestore() throws Exception {
        Integration fixture = integration();
        fixture.gateway.execute("set", List.of(key("a"), bytes("1")));
        fixture.gateway.execute("set", List.of(key("b"), bytes("2")));
        Path snapshot = dir.resolve("snap.bin");
        PersistentMvccIndex.save(snapshot,
                PersistentMvccIndex.snapshot(fixture.engine));
        MemTable storage = MemTable.create();
        MvccStorageEngine restored = PersistentMvccIndex.restore(
                snapshot, storage);
        assertThat(restored.latestValue(key("a"))).isEqualTo(bytes("1"));
        assertThat(restored.latestValue(key("b"))).isEqualTo(bytes("2"));
        storage.close();
        fixture.close();
    }

    @Test
    void autoTxnGcThenRestoreLatest() throws Exception {
        Integration fixture = integration();
        for (int i = 1; i <= 5; i++) {
            fixture.gateway.execute("set", List.of(key("k"), bytes("v" + i)));
        }
        BatchGcExecutor gc = new BatchGcExecutor(fixture.engine,
                GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE / 2));
        gc.gc();
        gc.close();
        Path snapshot = dir.resolve("gc.bin");
        PersistentMvccIndex.save(snapshot,
                PersistentMvccIndex.snapshot(fixture.engine));
        MvccStorageEngine restored = PersistentMvccIndex.restore(
                snapshot, MemTable.create());
        assertThat(restored.latestValue(key("k"))).isEqualTo(bytes("v5"));
        ((MemTable) restored.underlying()).close();
        fixture.close();
    }

    @Test
    void journalReplayThenGatewayReadsCommitted() throws Exception {
        Integration fixture = integration();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        // 模拟崩溃重启：重放日志后网关仍可读到已提交值
        TxnRecoveryReplay.RecoveryResult result =
                new TxnRecoveryReplay(fixture.engine, fixture.locks)
                        .replay(fixture.journal);
        assertThat(result.committed()).isZero(); // 已提交，无待恢复
        RespValue response = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void multiShardMsetThenIndexRestore() throws Exception {
        MultiShardIntegration fixture = multiShard();
        fixture.gateway.execute("mset", List.of(
                fixture.shard0Key, bytes("a"),
                fixture.shard1Key, bytes("b")));
        Path snapshot = dir.resolve("multi.bin");
        PersistentMvccIndex.save(snapshot,
                PersistentMvccIndex.snapshot(fixture.engine0));
        MvccStorageEngine restored = PersistentMvccIndex.restore(
                snapshot, MemTable.create());
        assertThat(restored.latestValue(fixture.shard0Key))
                .isEqualTo(bytes("a"));
        ((MemTable) restored.underlying()).close();
        fixture.close();
    }

    @Test
    void gatewayConflictThenRecoveryUnblocksNewTxn() throws Exception {
        Integration fixture = integration();
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                key("k"), bytes("blocked"), false, "stuck-txn",
                key("k"), 1, 60_000, System.currentTimeMillis(),
                java.util.Set.of());
        RespValue conflict = fixture.gateway.execute("set",
                List.of(key("k"), bytes("mine")));
        assertThat(conflict).isInstanceOf(io.tieringkv.protocol.RespError.class);
        // 超时恢复清理悬挂锁 → 新事务成功
        new TransactionRecoveryManager(fixture.engine, 0)
                .recover(fixture.locks, System.currentTimeMillis() + 120_000);
        assertThat(fixture.gateway.execute("set",
                List.of(key("k"), bytes("mine"))))
                .isEqualTo(new io.tieringkv.protocol.RespSimpleString("OK"));
        fixture.close();
    }

    @Test
    void recoveryCleansLocksThenGatewaySetSucceeds() throws Exception {
        Integration fixture = integration();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v1")));
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                key("k"), bytes("pending"), false, "hung-txn",
                key("k"), 4_000_000_000_000_000_000L, 60_000,
                System.currentTimeMillis(),
                java.util.Set.of());
        new TxnRecoveryReplay(fixture.engine, fixture.locks)
                .replay(fixture.journal);
        new TransactionRecoveryManager(fixture.engine, 0)
                .recover(fixture.locks, System.currentTimeMillis() + 120_000);
        assertThat(fixture.gateway.execute("set",
                List.of(key("k"), bytes("v2"))))
                .isEqualTo(new io.tieringkv.protocol.RespSimpleString("OK"));
        RespValue response = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("v2"));
        fixture.close();
    }

    @Test
    void incrementalRestoreWithGatewayWrites() throws Exception {
        Integration fixture = integration();
        fixture.gateway.execute("set", List.of(key("a"), bytes("1")));
        Path snapshot = dir.resolve("inc.bin");
        PersistentMvccIndex.save(snapshot,
                PersistentMvccIndex.snapshot(fixture.engine));
        fixture.gateway.execute("set", List.of(key("b"), bytes("2")));
        MvccStorageEngine restored = PersistentMvccIndex.restoreIncremental(
                snapshot, fixture.engine.underlying());
        assertThat(restored.latestValue(key("a"))).isEqualTo(bytes("1"));
        assertThat(restored.latestValue(key("b"))).isEqualTo(bytes("2"));
        ((MemTable) restored.underlying()).close();
        fixture.close();
    }

    @Test
    void metricsReflectFullFlow() throws Exception {
        Integration fixture = integration();
        fixture.gateway.execute("set", List.of(key("a"), bytes("1")));
        fixture.gateway.execute("get", List.of(key("a")));
        fixture.gateway.execute("del", List.of(key("a")));
        assertThat(fixture.txnMetrics.snapshot().committedTxn()).isEqualTo(2);
        assertThat(fixture.txnMetrics.snapshot().readTxn()).isEqualTo(1);
        assertThat(fixture.gatewayMetrics.snapshot().transactionTotal())
                .isEqualTo(2);
        fixture.close();
    }

    @Test
    void autoTxnDeleteSetAcrossRestart() throws Exception {
        Integration fixture = integration();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v1")));
        fixture.gateway.execute("del", List.of(key("k")));
        Path snapshot = dir.resolve("restart.bin");
        PersistentMvccIndex.save(snapshot,
                PersistentMvccIndex.snapshot(fixture.engine));
        MvccStorageEngine restored = PersistentMvccIndex.restore(
                snapshot, MemTable.create());
        assertThat(restored.latestValue(key("k"))).isNull();
        restored.putVersion(key("k"), bytes("v2"), 4_000_000_000_000_000_000L,
                4_000_000_000_000_000_001L,
                WriteType.PUT);
        assertThat(restored.latestValue(key("k"))).isEqualTo(bytes("v2"));
        ((MemTable) restored.underlying()).close();
        fixture.close();
    }

    @Test
    void chaosIntegrationNoPermanentLock() throws Exception {
        Integration fixture = integration();
        int threads = 4;
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean failed =
                new java.util.concurrent.atomic.AtomicBoolean();
        List<Thread> workers = new java.util.ArrayList<>();
        for (int w = 0; w < threads; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        RespValue response = fixture.gateway.execute("set",
                                List.of(key("hot" + (i % 10)),
                                        bytes("w" + writer + "-" + i)));
                        assertThat(response)
                                .isInstanceOfAny(
                                        io.tieringkv.protocol.RespSimpleString.class,
                                        io.tieringkv.protocol.RespError.class);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertThat(failed).isFalse();
        assertThat(fixture.locks.size()).isZero();
        fixture.close();
    }

    // ---------- helpers ----------

    private Integration integration() throws Exception {
        TimestampOracle oracle = new TimestampOracle();
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionMetricsRegistry txnMetrics = new TransactionMetricsRegistry();
        GatewayMetricsRegistry gatewayMetrics = new GatewayMetricsRegistry();
        Path journalPath = dir.resolve("int-" + System.nanoTime() + ".log");
        PersistentTxnJournal journal = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory());
        TransactionCoordinator coordinator =
                new TransactionCoordinator(oracle, 60_000, journal);
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(), coordinator,
                ignored -> new AutoTransactionExecutor.Participant(
                        "r1", engine, locks),
                txnMetrics);
        RedisClusterGateway gateway = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", engine.underlying()),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)),
                "n1", executor, gatewayMetrics);
        return new Integration(gateway, engine, locks, journal,
                txnMetrics, gatewayMetrics);
    }

    private MultiShardIntegration multiShard() throws Exception {
        TimestampOracle oracle = new TimestampOracle();
        MvccStorageEngine engine0 = new MvccStorageEngine(MemTable.create());
        MvccStorageEngine engine1 = new MvccStorageEngine(MemTable.create());
        LockTable locks0 = new LockTable();
        LockTable locks1 = new LockTable();
        AutoTransactionExecutor.Participant p0 =
                new AutoTransactionExecutor.Participant("r0", engine0, locks0);
        AutoTransactionExecutor.Participant p1 =
                new AutoTransactionExecutor.Participant("r1", engine1, locks1);
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(),
                new TransactionCoordinator(oracle, 60_000),
                key -> {
                    int slot = io.tieringkv.cluster.sharding.HashSlotRouter
                            .slot(key.key());
                    return slot < io.tieringkv.cluster.sharding.HashSlotRouter
                            .SLOT_COUNT / 2 ? p0 : p1;
                });
        RedisClusterGateway gateway = new RedisClusterGateway(2,
                Map.of(0, "n1", 1, "n1"),
                Map.of("n1", engine0.underlying()),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)),
                "n1", executor, new GatewayMetricsRegistry());
        byte[] shard0Key = null;
        byte[] shard1Key = null;
        for (int i = 0; i < 10_000 && (shard0Key == null || shard1Key == null);
             i++) {
            byte[] candidate = key("ms:" + i);
            int slot = io.tieringkv.cluster.sharding.HashSlotRouter
                    .slot(candidate);
            if (slot < io.tieringkv.cluster.sharding.HashSlotRouter.SLOT_COUNT / 2
                    && shard0Key == null) {
                shard0Key = candidate;
            } else if (slot >= io.tieringkv.cluster.sharding.HashSlotRouter
                    .SLOT_COUNT / 2
                    && shard1Key == null) {
                shard1Key = candidate;
            }
        }
        return new MultiShardIntegration(gateway, engine0, engine1,
                shard0Key, shard1Key);
    }

    private static byte[] key(String value) {
        return bytes("int:" + value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Integration(RedisClusterGateway gateway,
                               MvccStorageEngine engine, LockTable locks,
                               PersistentTxnJournal journal,
                               TransactionMetricsRegistry txnMetrics,
                               GatewayMetricsRegistry gatewayMetrics)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            journal.close();
            ((MemTable) engine.underlying()).close();
        }
    }

    private record MultiShardIntegration(RedisClusterGateway gateway,
                                         MvccStorageEngine engine0,
                                         MvccStorageEngine engine1,
                                         byte[] shard0Key, byte[] shard1Key)
            implements AutoCloseable {
        @Override
        public void close() {
            ((MemTable) engine0.underlying()).close();
            ((MemTable) engine1.underlying()).close();
        }
    }
}
