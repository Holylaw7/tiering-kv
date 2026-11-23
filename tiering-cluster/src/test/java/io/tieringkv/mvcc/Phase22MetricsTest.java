package io.tieringkv.mvcc;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.metrics.MetricsExporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 22 指标升级：expired / abort reason / lock 指标 / INFO TRANSACTION。 */
class Phase22MetricsTest {

    @Test
    void expiredTotalRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordExpired();
        assertThat(metrics.snapshot().expiredTotal()).isEqualTo(1);
    }

    @Test
    void abortReasonRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordAbortReason("expired");
        metrics.recordAbortReason("expired");
        metrics.recordAbortReason("conflict");
        assertThat(metrics.snapshot().abortReasons().get("expired").sum())
                .isEqualTo(2);
        assertThat(metrics.snapshot().abortReasons().get("conflict").sum())
                .isEqualTo(1);
    }

    @Test
    void lockMetricsRecorded() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordLock();
        metrics.recordLockResolve();
        assertThat(metrics.snapshot().lockTotal()).isEqualTo(1);
        assertThat(metrics.snapshot().lockResolveTotal()).isEqualTo(1);
    }

    @Test
    void longRunningGauge() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.setLongRunning(3);
        assertThat(metrics.snapshot().longRunning()).isEqualTo(3);
    }

    @Test
    void lockWaitSecondsAlias() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordLockWait(2_000_000);
        assertThat(metrics.snapshot().lockWaitMs()).isEqualTo(2.0);
        assertThat(metrics.sectionText()).contains("lock_wait_seconds:0.002");
    }

    @Test
    void infoTransactionContainsLifecycleFields() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordExpired();
        metrics.setLongRunning(2);
        metrics.recordLock();
        metrics.recordLockResolve();
        String info = metrics.sectionText();
        assertThat(info).contains("txn_expired_total:1");
        assertThat(info).contains("txn_long_running:2");
        assertThat(info).contains("lock_total:1");
        assertThat(info).contains("lock_resolve_total:1");
        assertThat(info).contains("lock_wait_seconds:");
    }

    @Test
    void exporterContainsLifecycleMetrics() {
        String exported = export();
        assertThat(exported).contains("txn_expired_total");
        assertThat(exported).contains("txn_long_running");
        assertThat(exported).contains("lock_total");
        assertThat(exported).contains("lock_resolve_total");
        assertThat(exported).contains("lock_wait_time_ms");
    }

    @Test
    void exporterContainsAbortReasonLabels() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordAbortReason("expired");
        String exported = MetricsExporter.export(
                new io.tieringkv.cluster.region.RegionMetricsRegistry(),
                new io.tieringkv.cluster.raft.RaftMetricsRegistry(),
                new io.tieringkv.cluster.migration.MigrationMetricsRegistry(),
                new GatewayMetricsRegistry(), metrics,
                new MvccMetricsRegistry());
        assertThat(exported).contains("txn_abort_reason_expired");
    }

    @Test
    void txnActiveStillTracked() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        assertThat(metrics.snapshot().activeTxn()).isEqualTo(1);
        metrics.recordCommit(1_000_000);
        assertThat(metrics.snapshot().activeTxn()).isZero();
    }

    @Test
    void txnCommitLatencyTracked() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordCommit(5_000_000);
        assertThat(metrics.snapshot().commitLatencyMs()).isEqualTo(5.0);
    }

    @Test
    void infoContainsPreparedAndLockedKeys() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.setLockCount(7);
        assertThat(metrics.sectionText()).contains("lock_count:7");
        assertThat(metrics.sectionText()).contains("active_txn:0");
    }

    @Test
    void concurrentAbortReasonsSafe() throws Exception {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        int threads = 8;
        java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int w = 0; w < threads; w++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        metrics.recordAbortReason("timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertThat(metrics.snapshot().abortReasons().get("timeout").sum())
                .isEqualTo(800);
    }

    @Test
    void expiredAfterRollbackStillCounted() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordBegin();
        metrics.recordRollback();
        metrics.recordExpired();
        assertThat(metrics.snapshot().rollbackTxn()).isEqualTo(1);
        assertThat(metrics.snapshot().expiredTotal()).isEqualTo(1);
        assertThat(metrics.snapshot().activeTxn()).isZero();
    }

    @Test
    void lockResolveCacheMissCounted() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordLockResolve();
        metrics.recordLockResolve();
        assertThat(metrics.snapshot().lockResolveTotal()).isEqualTo(2);
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
