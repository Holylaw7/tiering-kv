package io.tieringkv.cluster.migration;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** 迁移指标（Phase 17）：migration_bytes / migration_speed。 */
public final class MigrationMetricsRegistry {

    private final LongAdder bytes = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private volatile long remaining;
    private final long startedAt = System.nanoTime();

    public void recordBytes(long migratedBytes) {
        bytes.add(Math.max(0, migratedBytes));
    }

    public void recordError() {
        errors.increment();
    }

    public void setRemaining(long remaining) {
        this.remaining = Math.max(0, remaining);
    }

    public Snapshot snapshot() {
        long total = bytes.sum();
        double seconds = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000_000.0);
        return new Snapshot(total, total / 1024.0 / 1024.0 / seconds,
                remaining, errors.sum());
    }

    public String sectionText() {
        return "# Migration\r\n" + metricLines();
    }

    public String metricLines() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "migration_bytes:%d\r\n"
                        + "migration_speed_mb_per_sec:%.1f\r\n"
                        + "migration_remaining:%d\r\n"
                        + "migration_error:%d\r\n",
                s.migrationBytes(), s.migrationSpeedMbPerSec(),
                s.migrationRemaining(), s.migrationErrors());
    }

    public record Snapshot(long migrationBytes, double migrationSpeedMbPerSec,
                           long migrationRemaining, long migrationErrors) {
    }
}
