package io.tieringkv.observability;

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
        return new Snapshot(backups.sum(), backupBytes.sum(),
                restores.sum(), restoreBytes.sum(), pitrWatermark);
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "backup_total:%d\r\n"
                        + "backup_bytes:%d\r\n"
                        + "restore_total:%d\r\n"
                        + "restore_bytes:%d\r\n"
                        + "backup_pitr_watermark:%d\r\n",
                s.backups(), s.backupBytes(), s.restores(),
                s.restoreBytes(), s.pitrWatermark());
    }

    public record Snapshot(long backups, long backupBytes,
                           long restores, long restoreBytes,
                           long pitrWatermark) {
    }
}
