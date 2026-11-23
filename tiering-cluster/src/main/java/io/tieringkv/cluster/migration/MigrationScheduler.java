package io.tieringkv.cluster.migration;

import java.util.function.Supplier;

/**
 * 迁移调度器（Phase 18）：按 IO 压力降 worker、按 backlog 增并发，
 * 边界 [1, maxWorkers]。
 */
public final class MigrationScheduler {

    private final int maxWorkers;
    private final long ioThreshold;
    private final long backlogThreshold;
    private final Supplier<Long> ioPressure;
    private final Supplier<Long> backlog;
    private int workers;

    public MigrationScheduler(int initialWorkers, int maxWorkers,
                              long ioThreshold, long backlogThreshold,
                              Supplier<Long> ioPressure,
                              Supplier<Long> backlog) {
        this.maxWorkers = Math.max(1, maxWorkers);
        this.ioThreshold = ioThreshold;
        this.backlogThreshold = backlogThreshold;
        this.ioPressure = ioPressure;
        this.backlog = backlog;
        this.workers = Math.max(1, Math.min(initialWorkers, this.maxWorkers));
    }

    public synchronized int adjustWorkers() {
        if (ioPressure.get() > ioThreshold && workers > 1) {
            workers--;
        } else if (backlog.get() > backlogThreshold && workers < maxWorkers) {
            workers++;
        }
        return workers;
    }

    public synchronized int workers() {
        return workers;
    }

    public int maxWorkers() {
        return maxWorkers;
    }
}
