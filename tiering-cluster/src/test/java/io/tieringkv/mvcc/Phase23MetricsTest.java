package io.tieringkv.mvcc;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.metrics.MetricsExporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 23 指标：生命周期/锁/运行时延续。 */
class Phase23MetricsTest {

    @Test
    void expiredAndLongRunningTracked() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordExpired();
        metrics.setLongRunning(5);
        assertThat(metrics.snapshot().expiredTotal()).isEqualTo(1);
        assertThat(metrics.snapshot().longRunning()).isEqualTo(5);
    }

    @Test
    void lockResolveCounted() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.recordLockResolve();
        metrics.recordLock();
        assertThat(metrics.snapshot().lockResolveTotal()).isEqualTo(1);
        assertThat(metrics.snapshot().lockTotal()).isEqualTo(1);
    }

    @Test
    void infoContainsRuntimeFields() {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        metrics.setLongRunning(2);
        metrics.recordExpired();
        String info = metrics.sectionText();
        assertThat(info).contains("txn_long_running:2");
        assertThat(info).contains("txn_expired_total:1");
        assertThat(info).contains("lock_wait_seconds:");
    }

    @Test
    void exporterContainsRuntimeMetrics() {
        String exported = export();
        assertThat(exported).contains("txn_expired_total");
        assertThat(exported).contains("txn_long_running");
        assertThat(exported).contains("lock_resolve_total");
    }

    @ParameterizedTest(name = "expired {0}")
    @ValueSource(ints = {1, 2, 4, 8, 3, 6})
    void parameterizedExpired(int count) {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        for (int i = 0; i < count; i++) {
            metrics.recordExpired();
        }
        assertThat(metrics.snapshot().expiredTotal()).isEqualTo(count);
    }

    @ParameterizedTest(name = "reasons {0}")
    @ValueSource(ints = {1, 3, 5, 2, 4, 7})
    void parameterizedAbortReasons(int count) {
        TransactionMetricsRegistry metrics = new TransactionMetricsRegistry();
        for (int i = 0; i < count; i++) {
            metrics.recordAbortReason("expired");
        }
        assertThat(metrics.snapshot().abortReasons().get("expired").sum())
                .isEqualTo(count);
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
