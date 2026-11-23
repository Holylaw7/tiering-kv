package io.tieringkv.mvcc;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.metrics.MetricsExporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 21 可观测性：prepare/网络重试/锁等待/Region 数/恢复时间。 */
class Phase21MetricsTest {

    @Test
    void prepareLatencyRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordPrepare(5_000_000);
        assertThat(metrics.snapshot().prepareLatencyMs()).isEqualTo(5.0);
    }

    @Test
    void networkRetryRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordNetworkRetry();
        metrics.recordNetworkRetry();
        assertThat(metrics.snapshot().networkRetry()).isEqualTo(2);
    }

    @Test
    void lockWaitRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordLockWait(2_000_000);
        assertThat(metrics.snapshot().lockWaitMs()).isEqualTo(2.0);
    }

    @Test
    void regionCountRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordRegionCount(3);
        assertThat(metrics.snapshot().regionCount()).isEqualTo(3);
    }

    @Test
    void recoveryTimeRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordRecovery();
        metrics.recordRecoveryTime(10_000_000);
        assertThat(metrics.snapshot().recoveryTimeMs()).isEqualTo(10.0);
    }

    @Test
    void infoTransactionContainsNetworkMetrics() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordPrepare(1_000_000);
        metrics.recordNetworkRetry();
        metrics.recordLockWait(500_000);
        metrics.recordRegionCount(2);
        String info = metrics.sectionText();
        assertThat(info).contains("txn_prepare_latency_ms:");
        assertThat(info).contains("txn_network_retry:1");
        assertThat(info).contains("txn_lock_wait_ms:");
        assertThat(info).contains("txn_region_count:2");
        assertThat(info).contains("txn_recovery_time_ms:");
    }

    @Test
    void exporterContainsPrepareLatency() {
        assertThat(export()).contains("txn_prepare_latency_ms");
    }

    @Test
    void exporterContainsNetworkRetry() {
        assertThat(export()).contains("txn_network_retry");
    }

    @Test
    void exporterContainsLockWait() {
        assertThat(export()).contains("txn_lock_wait_ms");
    }

    @Test
    void exporterContainsRegionCount() {
        assertThat(export()).contains("txn_region_count");
    }

    @Test
    void exporterContainsRecoveryTime() {
        assertThat(export()).contains("txn_recovery_time_ms");
    }

    @Test
    void exporterContainsCompactionMetrics() {
        MvccMetricsRegistry mvcc = new MvccMetricsRegistry();
        mvcc.recordCompaction(10, 100);
        String exported = MetricsExporter.export(
                new io.tieringkv.cluster.region.RegionMetricsRegistry(),
                new io.tieringkv.cluster.raft.RaftMetricsRegistry(),
                new io.tieringkv.cluster.migration.MigrationMetricsRegistry(),
                new GatewayMetricsRegistry(),
                new TransactionMetricsRegistry(), mvcc);
        assertThat(exported).contains("mvcc_compaction_versions");
        assertThat(exported).contains("mvcc_compaction_bytes");
    }

    @Test
    void mvccMetricsContainCompactionLines() {
        MvccMetricsRegistry metrics = new MvccMetricsRegistry();
        metrics.recordCompaction(5, 50);
        assertThat(metrics.metricLines())
                .contains("mvcc_compaction_versions:5");
        assertThat(metrics.metricLines())
                .contains("mvcc_compaction_bytes:50");
    }

    @Test
    void gatewayMetricsUnaffectedByTxnMetrics() {
        GatewayMetricsRegistry gateway = new GatewayMetricsRegistry();
        gateway.recordRequest(1_000_000);
        assertThat(gateway.snapshot().avgLatencyMs()).isEqualTo(1.0);
    }

    @Test
    void rollbackTotalRemainsTracked() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordRollback();
        assertThat(metrics.snapshot().rollbackTxn()).isEqualTo(1);
        assertThat(metrics.snapshot().activeTxn()).isZero();
    }

    @Test
    void prepareLatencyAveragesAcrossBegins() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordPrepare(2_000_000);
        metrics.recordBegin();
        metrics.recordPrepare(4_000_000);
        assertThat(metrics.snapshot().prepareLatencyMs()).isEqualTo(3.0);
    }

    @Test
    void regionCountDefaultZero() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        assertThat(metrics.snapshot().regionCount()).isZero();
    }

    private static String export() {
        return MetricsExporter.export(
                new io.tieringkv.cluster.region.RegionMetricsRegistry(),
                new io.tieringkv.cluster.raft.RaftMetricsRegistry(),
                new io.tieringkv.cluster.migration.MigrationMetricsRegistry(),
                new GatewayMetricsRegistry(),
                new TransactionMetricsRegistry(),
                new MvccMetricsRegistry());
    }
}
