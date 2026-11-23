package io.tieringkv.observability;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/**
 * 复制指标（ADR-0344）：LagTracker 水位 + 复制/抑制/冲突计数。
 * 管线喂数接入列为 Phase 增量（本期提供 record API）。
 */
public final class ReplicationMetricsRegistry {

    private ReplicationLagView lagTracker;
    private final LongAdder replicated = new LongAdder();
    private final LongAdder suppressed = new LongAdder();
    private final LongAdder conflicts = new LongAdder();

    public ReplicationMetricsRegistry() {
        this(null);
    }

    public ReplicationMetricsRegistry(ReplicationLagView lagTracker) {
        this.lagTracker = lagTracker;
    }

    /** 绑定管线内部 LagTracker（ADR-0345）：复制成功后水位可见。 */
    public void attachLagTracker(ReplicationLagView lagTracker) {
        this.lagTracker = lagTracker;
    }

    public void recordReplicated() {
        replicated.increment();
    }

    public void recordSuppressed() {
        suppressed.increment();
    }

    public void recordConflict() {
        conflicts.increment();
    }

    /** 记录副本水位（无 LagTracker 时 no-op）。 */
    public void applied(String replicaId, long seq) {
        if (lagTracker != null) {
            lagTracker.applied(replicaId, seq);
        }
    }

    public Snapshot snapshot(long nowMillis) {
        long replicas = lagTracker == null ? 0
                : lagTracker.replicaCount();
        long maxLagMillis = lagTracker == null ? 0
                : lagTracker.maxLagMillis(nowMillis);
        return new Snapshot(replicas, maxLagMillis,
                replicated.sum(), suppressed.sum(), conflicts.sum());
    }

    public String metricLines() {
        Snapshot s = snapshot(System.currentTimeMillis());
        return String.format(Locale.ROOT,
                "replication_replicas:%d\r\n"
                        + "replication_max_lag_ms:%d\r\n"
                        + "replication_replicated:%d\r\n"
                        + "replication_suppressed:%d\r\n"
                        + "replication_conflicts:%d\r\n",
                s.replicas(), s.maxLagMillis(), s.replicated(),
                s.suppressed(), s.conflicts());
    }

    public record Snapshot(long replicas, long maxLagMillis,
                           long replicated, long suppressed,
                           long conflicts) {
    }
}
