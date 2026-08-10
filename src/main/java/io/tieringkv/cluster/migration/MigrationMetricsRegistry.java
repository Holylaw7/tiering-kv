package io.tieringkv.cluster.migration;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** 迁移指标（Phase 17）：migration_bytes / migration_speed。 */
public final class MigrationMetricsRegistry {

    private final LongAdder bytes = new LongAdder();
    private final long startedAt = System.nanoTime();

    public void recordBytes(long migratedBytes) {
        bytes.add(Math.max(0, migratedBytes));
    }

    public Snapshot snapshot() {
        long total = bytes.sum();
        double seconds = Math.max(1, (System.nanoTime() - startedAt) / 1_000_000_000.0);
        return new Snapshot(total, total / 1024.0 / 1024.0 / seconds);
    }

    public String sectionText() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "# Migration\r\n"
                        + "migration_bytes:%d\r\n"
                        + "migration_speed_mb_per_sec:%.1f\r\n",
                s.migrationBytes(), s.migrationSpeedMbPerSec());
    }

    public record Snapshot(long migrationBytes, double migrationSpeedMbPerSec) {
    }
}
