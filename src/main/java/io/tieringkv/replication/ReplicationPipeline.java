package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多地域复制管道（ADR-0108）：CDC 事件 → 各地域副本。
 * ASYNC 即投即确认；SYNC 等待全部 ack（带超时）。
 */
public final class ReplicationPipeline {

    private final List<ReplicaSink> replicas;
    private final ReplicationMode mode;
    private final long syncTimeoutMillis;
    private final LagTracker lagTracker;
    private final ConflictDetector conflictDetector;
    private final String originRegion;
    private final AtomicLong replicated = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();

    public ReplicationPipeline(List<ReplicaSink> replicas,
                               ReplicationMode mode,
                               long syncTimeoutMillis,
                               String originRegion) {
        this.replicas = List.copyOf(replicas);
        this.mode = mode;
        this.syncTimeoutMillis = syncTimeoutMillis;
        this.lagTracker = new LagTracker();
        this.conflictDetector = new ConflictDetector();
        this.originRegion = originRegion;
    }

    public CompletableFuture<Boolean> replicate(ChangeEvent event) {
        boolean conflicted = conflictDetector.observe(event, originRegion);
        if (conflicted) {
            conflicts.incrementAndGet();
        }
        if (mode == ReplicationMode.ASYNC) {
            for (ReplicaSink replica : replicas) {
                replica.apply(event).whenComplete((ignored, error) -> {
                    if (error == null) {
                        lagTracker.applied(replica.replicaId(),
                                event.seq());
                    }
                });
            }
            replicated.incrementAndGet();
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Void>[] futures = replicas.stream()
                .map(replica -> replica.apply(event).whenComplete(
                        (ignored, error) -> {
                            if (error == null) {
                                lagTracker.applied(replica.replicaId(),
                                        event.seq());
                            }
                        }))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures)
                .orTimeout(syncTimeoutMillis, TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> {
                    if (error != null) {
                        throw new CompletionException(
                                "sync replication timeout/failure", error);
                    }
                    replicated.incrementAndGet();
                    return true;
                });
    }

    public long replicatedCount() {
        return replicated.get();
    }

    public long conflictCount() {
        return conflicts.get();
    }

    public LagTracker lagTracker() {
        return lagTracker;
    }

    public ConflictDetector conflictDetector() {
        return conflictDetector;
    }
}
