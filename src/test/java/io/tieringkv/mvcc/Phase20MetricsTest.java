package io.tieringkv.mvcc;

import io.tieringkv.cluster.gateway.AutoTransactionExecutor;
import io.tieringkv.cluster.gateway.RedisClusterGateway;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.metrics.MetricsExporter;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 20 可观测性（ADR-0070/0079）：事务/MVCC/网关指标 + INFO + Prometheus。 */
class Phase20MetricsTest {

    @Test
    void transactionMetricsCounters() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordCommit(1_000_000);
        metrics.recordBegin();
        metrics.recordRollback();
        metrics.recordBegin();
        metrics.recordAbort();
        metrics.recordConflict();
        metrics.recordRecovery();
        metrics.recordRead();
        TransactionMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.beginTotal()).isEqualTo(3);
        assertThat(s.committedTxn()).isEqualTo(1);
        assertThat(s.rollbackTxn()).isEqualTo(1);
        assertThat(s.abortTxn()).isEqualTo(1);
        assertThat(s.conflictTxn()).isEqualTo(1);
        assertThat(s.recoveryTxn()).isEqualTo(1);
        assertThat(s.readTxn()).isEqualTo(1);
        assertThat(s.activeTxn()).isZero();
        assertThat(s.commitLatencyMs()).isEqualTo(1.0);
    }

    @Test
    void infoTransactionSection() {
        Fixture fixture = fixture();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        String info = info(fixture);
        assertThat(info).contains("# Transaction");
        assertThat(info).contains("committed_txn:1");
        assertThat(info).contains("begin_txn:1");
        fixture.close();
    }

    @Test
    void infoMvccSection() {
        Fixture fixture = fixture();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        String info = info(fixture);
        assertThat(info).contains("# MVCC");
        assertThat(info).contains("mvcc_versions_total:1");
        fixture.close();
    }

    @Test
    void infoIncludesGatewaySection() {
        Fixture fixture = fixture();
        String info = info(fixture);
        assertThat(info).contains("# Gateway");
        assertThat(info).contains("gateway_connections:0");
        fixture.close();
    }

    @Test
    void redisTxnLatencyRecorded() {
        Fixture fixture = fixture();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        fixture.gateway.execute("del", List.of(key("k")));
        assertThat(fixture.gatewayMetrics.snapshot().transactionTotal())
                .isEqualTo(2);
        assertThat(fixture.gatewayMetrics.snapshot().transactionLatencyMs())
                .isGreaterThan(0);
        fixture.close();
    }

    @Test
    void exporterIncludesTxnAbort() {
        String exported = export();
        assertThat(exported).contains("txn_abort_total");
    }

    @Test
    void exporterIncludesTxnRecovery() {
        assertThat(export()).contains("txn_recovery_total");
    }

    @Test
    void exporterIncludesMvccVersions() {
        assertThat(export()).contains("mvcc_versions_total");
    }

    @Test
    void exporterIncludesMvccGcDeleted() {
        assertThat(export()).contains("mvcc_gc_deleted_versions");
    }

    @Test
    void exporterIncludesRedisTxnLatency() {
        assertThat(export()).contains("redis_txn_latency_ms");
    }

    @Test
    void mvccMetricLinesContainDeletedVersions() {
        MvccMetricsRegistry registry = new MvccMetricsRegistry();
        registry.recordGc(10, 100);
        assertThat(registry.metricLines()).contains("mvcc_gc_deleted_versions:10");
        assertThat(registry.metricLines()).contains("mvcc_versions_total:0");
    }

    @Test
    void gatewayMetricLinesContainTxnLatency() {
        GatewayMetricsRegistry registry = new GatewayMetricsRegistry();
        registry.recordTransaction(5_000_000);
        assertThat(registry.metricLines()).contains("redis_txn_latency_ms:");
        assertThat(registry.metricLines()).contains("redis_txn_total:1");
    }

    @Test
    void autoTransactionUpdatesMetrics() {
        Fixture fixture = fixture();
        fixture.gateway.execute("set", List.of(key("a"), bytes("1")));
        fixture.gateway.execute("set", List.of(key("b"), bytes("2")));
        fixture.gateway.execute("get", List.of(key("a")));
        TransactionMetricsRegistry.Snapshot s =
                fixture.txnMetrics.snapshot();
        assertThat(s.committedTxn()).isEqualTo(2);
        assertThat(s.readTxn()).isEqualTo(1);
        fixture.close();
    }

    @Test
    void conflictIncrementsConflictMetric() {
        Fixture fixture = fixture();
        new PrewriteExecutor().prewrite(fixture.engine, fixture.locks,
                key("k"), bytes("blocked"), false,
                "other-txn", key("k"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        fixture.gateway.execute("set", List.of(key("k"), bytes("mine")));
        assertThat(fixture.txnMetrics.snapshot().conflictTxn())
                .isGreaterThanOrEqualTo(1);
        fixture.close();
    }

    @Test
    void mvccSafePointAndVersionsReflectGc() {
        MvccMetricsRegistry registry = new MvccMetricsRegistry();
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int v = 1; v <= 5; v++) {
            engine.putVersion(key("k"), bytes("v" + v), v, v * 10,
                    WriteType.PUT);
        }
        io.tieringkv.mvcc.gc.BatchGcExecutor gc =
                new io.tieringkv.mvcc.gc.BatchGcExecutor(engine,
                        io.tieringkv.mvcc.gc.GcConfig.DEFAULT, registry);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        MvccMetricsRegistry.Snapshot s = registry.snapshot();
        assertThat(s.gcVersions()).isEqualTo(4);
        assertThat(s.safePoint()).isEqualTo(100);
        assertThat(s.versions()).isEqualTo(1);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void infoSectionsIncludeReadCounters() {
        Fixture fixture = fixture();
        fixture.gateway.execute("mget", List.of(key("a"), key("b")));
        assertThat(info(fixture)).contains("read_txn:2");
        fixture.close();
    }

    // ---------- helpers ----------

    private static String export() {
        return MetricsExporter.export(
                new io.tieringkv.cluster.region.RegionMetricsRegistry(),
                new io.tieringkv.cluster.raft.RaftMetricsRegistry(),
                new io.tieringkv.cluster.migration.MigrationMetricsRegistry(),
                new GatewayMetricsRegistry(),
                new TransactionMetricsRegistry(),
                new MvccMetricsRegistry());
    }

    private static Fixture fixture() {
        TimestampOracle oracle = new TimestampOracle();
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        TransactionMetricsRegistry txnMetrics = new TransactionMetricsRegistry();
        GatewayMetricsRegistry gatewayMetrics = new GatewayMetricsRegistry();
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(),
                new TransactionCoordinator(oracle, 60_000),
                ignored -> new AutoTransactionExecutor.Participant(
                        "r1", engine, locks),
                txnMetrics);
        RedisClusterGateway gateway = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", engine.underlying()),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)),
                "n1", executor, gatewayMetrics);
        return new Fixture(gateway, engine, locks, txnMetrics, gatewayMetrics);
    }

    private static String info(Fixture fixture) {
        io.tieringkv.protocol.RespValue response = fixture.gateway.execute(
                "info", List.of());
        return new String(((io.tieringkv.protocol.RespBulkString) response)
                .bytes(), StandardCharsets.UTF_8);
    }

    private static byte[] key(String value) {
        return bytes("metric:" + value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(RedisClusterGateway gateway,
                           MvccStorageEngine engine, LockTable locks,
                           TransactionMetricsRegistry txnMetrics,
                           GatewayMetricsRegistry gatewayMetrics)
            implements AutoCloseable {
        @Override
        public void close() {
            ((MemTable) engine.underlying()).close();
        }
    }
}
