package io.tieringkv.observability;

import io.tieringkv.replication.LagTracker;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * 备份/恢复指标（ADR-0344）：次数/字节 + PITR watermark。
 * 由 BackupManager/RestoreManager additive 重载喂入。
 */
public final class BackupMetricsRegistry {

    private final LongAdder backups = new LongAdder();
    private final LongAdder backupBytes = new LongAdder();
    private final LongAdder restores = new LongAdder();
    private final LongAdder restoreBytes = new LongAdder();
    private volatile long pitrWatermark;
    private LagTracker lagTracker;

    public BackupMetricsRegistry() {
        this(null);
    }

    /** 复制水位联动（ADR-0344 收口）：备份时可见复制滞后。 */
    public BackupMetricsRegistry(LagTracker lagTracker) {
        this.lagTracker = lagTracker;
    }

    public void attachLagTracker(LagTracker lagTracker) {
        this.lagTracker = lagTracker;
    }

    public void recordBackup(long bytes) {
        backups.increment();
        backupBytes.add(bytes);
    }

    public void recordRestore(long bytes) {
        restores.increment();
        restoreBytes.add(bytes);
    }

    public void setPitrWatermark(long watermark) {
        this.pitrWatermark = watermark;
    }

    public Snapshot snapshot() {
        long maxLag = 0;
        if (lagTracker != null) {
            long now = System.currentTimeMillis();
            for (String replicaId : lagTracker.snapshot().keySet()) {
                maxLag = Math.max(maxLag,
                        lagTracker.lagMillis(replicaId, now));
            }
        }
        return new Snapshot(backups.sum(), backupBytes.sum(),
                restores.sum(), restoreBytes.sum(), pitrWatermark,
                maxLag);
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "backup_total:%d\r\n"
                        + "backup_bytes:%d\r\n"
                        + "restore_total:%d\r\n"
                        + "restore_bytes:%d\r\n"
                        + "backup_pitr_watermark:%d\r\n"
                        + "backup_replication_max_lag_ms:%d\r\n",
                s.backups(), s.backupBytes(), s.restores(),
                s.restoreBytes(), s.pitrWatermark(),
                s.replicationMaxLagMillis());
    }

    public record Snapshot(long backups, long backupBytes,
                           long restores, long restoreBytes,
                           long pitrWatermark,
                           long replicationMaxLagMillis) {
    }
}
