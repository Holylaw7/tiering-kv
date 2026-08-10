package io.tieringkv.mvcc;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.metrics.MetricsExporter;
import io.tieringkv.cluster.migration.MigrationMetricsRegistry;
import io.tieringkv.cluster.raft.RaftMetricsRegistry;
import io.tieringkv.cluster.region.RegionMetricsRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 19 指标：事务/MVCC 指标 + Prometheus 输出。 */
class Phase19MetricsTest {

    @Test
    void txnMetricsTracked() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordCommit(1_000_000);
        metrics.recordRollback();
        metrics.recordConflict();
        metrics.setLockCount(3);
        TransactionMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.beginTotal()).isEqualTo(1);
        assertThat(s.committedTxn()).isEqualTo(1);
        assertThat(s.rollbackTxn()).isEqualTo(1);
        assertThat(s.conflictTxn()).isEqualTo(1);
        assertThat(s.lockCount()).isEqualTo(3);
        assertThat(s.commitLatencyMs()).isEqualTo(1.0);
    }

    @Test
    void txnSectionText() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        String text = metrics.sectionText();
        assertThat(text).startsWith("# Transaction")
                .contains("begin_txn:1", "active_txn:1", "lock_count:0");
    }

    @Test
    void mvccMetricsTracked() {
        MvccMetricsRegistry metrics = new MvccMetricsRegistry();
        metrics.recordRead();
        metrics.recordWrite();
        metrics.recordGc(10, 2048);
        metrics.setVersions(100);
        metrics.setSafePoint(42);
        MvccMetricsRegistry.Snapshot s = metrics.snapshot();
        assertThat(s.versions()).isEqualTo(100);
        assertThat(s.gcVersions()).isEqualTo(10);
        assertThat(s.gcBytes()).isEqualTo(2048);
        assertThat(s.safePoint()).isEqualTo(42);
    }

    @Test
    void mvccMetricLines() {
        MvccMetricsRegistry metrics = new MvccMetricsRegistry();
        metrics.setVersions(7);
        assertThat(metrics.metricLines()).contains(
                "mvcc_versions_total:7",
                "mvcc_gc_versions:0",
                "mvcc_safe_point:");
    }

    @Test
    void exporterIncludesTxnMetrics() {
        TransactionMetricsRegistry txn = new TransactionMetricsRegistry();
        txn.recordBegin();
        txn.recordCommit(500_000);
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), new GatewayMetricsRegistry(),
                txn, new MvccMetricsRegistry());
        assertThat(text).contains("txn_begin_total 1")
                .contains("txn_commit_total 1")
                .contains("txn_active 0")
                .contains("txn_lock_count 0");
    }

    @Test
    void exporterIncludesMvccMetrics() {
        MvccMetricsRegistry mvcc = new MvccMetricsRegistry();
        mvcc.recordRead();
        mvcc.recordGc(5, 100);
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), new GatewayMetricsRegistry(),
                new TransactionMetricsRegistry(), mvcc);
        assertThat(text).contains("mvcc_read_qps 1")
                .contains("mvcc_gc_versions_total 5");
    }

    @Test
    void txnLatencyMultipleSamples() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordCommit(500_000);
        metrics.recordBegin();
        metrics.recordCommit(1_500_000);
        assertThat(metrics.snapshot().commitLatencyMs()).isEqualTo(1.0);
    }

    @Test
    void activeTxnCount() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordBegin();
        assertThat(metrics.snapshot().activeTxn()).isEqualTo(2);
        metrics.recordCommit(1);
        assertThat(metrics.snapshot().activeTxn()).isEqualTo(1);
    }

    @Test
    void rollbackDecrementsActive() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordRollback();
        assertThat(metrics.snapshot().activeTxn()).isZero();
    }

    @Test
    void exporterEmptyRegistriesValid() {
        String text = MetricsExporter.export(
                new RegionMetricsRegistry(), new RaftMetricsRegistry(),
                new MigrationMetricsRegistry(), new GatewayMetricsRegistry(),
                new TransactionMetricsRegistry(), new MvccMetricsRegistry());
        assertThat(text).contains("txn_begin_total 0")
                .contains("mvcc_safe_point");
    }
}
