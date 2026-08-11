package io.tieringkv.replication.active;

/** Active-Active 冲突指标（ADR-0135）。 */
public final class ConflictMetrics {

    private final java.util.concurrent.atomic.AtomicLong conflicts =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong lastConflictAt =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.List<Long> convergenceSamples =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void recordConflict() {
        conflicts.incrementAndGet();
        lastConflictAt.set(System.currentTimeMillis());
    }

    public void recordConvergence(long millis) {
        convergenceSamples.add(millis);
    }

    public long conflicts() {
        return conflicts.get();
    }

    public long lastConflictAt() {
        return lastConflictAt.get();
    }

    public double avgConvergenceMillis() {
        if (convergenceSamples.isEmpty()) {
            return 0;
        }
        return convergenceSamples.stream().mapToLong(Long::longValue)
                .average().orElse(0);
    }
}
