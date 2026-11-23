package io.tieringkv.mvcc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** 事务指标（Phase 19）：begin/commit/rollback/conflict/延迟/活跃/锁。 */
public final class TransactionMetricsRegistry {

    private final LongAdder begins = new LongAdder();
    private final LongAdder commits = new LongAdder();
    private final LongAdder rollbacks = new LongAdder();
    private final LongAdder conflicts = new LongAdder();
    private final LongAdder aborts = new LongAdder();
    private final LongAdder recoveries = new LongAdder();
    private final LongAdder reads = new LongAdder();
    private final LongAdder commitLatencyNanos = new LongAdder();
    private final LongAdder prepareLatencyNanos = new LongAdder();
    private final LongAdder networkRetries = new LongAdder();
    private final LongAdder lockWaitNanos = new LongAdder();
    private final LongAdder recoveryTimeNanos = new LongAdder();
    private final AtomicLong regionCount = new AtomicLong();
    private final LongAdder expiredTotal = new LongAdder();
    private final LongAdder lockTotal = new LongAdder();
    private final LongAdder lockResolveTotal = new LongAdder();
    private final AtomicLong longRunning = new AtomicLong();
    private final Map<String, LongAdder> abortReasons =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong active = new AtomicLong();
    private final AtomicLong lockCount = new AtomicLong();

    public void recordBegin() {
        begins.increment();
        active.incrementAndGet();
    }

    public void recordCommit(long latencyNanos) {
        commits.increment();
        active.decrementAndGet();
        commitLatencyNanos.add(latencyNanos);
    }

    public void recordRollback() {
        rollbacks.increment();
        active.decrementAndGet();
    }

    public void recordConflict() {
        conflicts.increment();
    }

    public void recordAbort() {
        aborts.increment();
        active.decrementAndGet();
    }

    public void recordRecovery() {
        recoveries.increment();
    }

    public void recordRead() {
        reads.increment();
    }

    public void recordPrepare(long latencyNanos) {
        prepareLatencyNanos.add(latencyNanos);
    }

    public void recordNetworkRetry() {
        networkRetries.increment();
    }

    public void recordLockWait(long latencyNanos) {
        lockWaitNanos.add(latencyNanos);
    }

    public void recordRegionCount(int count) {
        regionCount.set(count);
    }

    public void recordRecoveryTime(long latencyNanos) {
        recoveryTimeNanos.add(latencyNanos);
    }

    public void recordExpired() {
        expiredTotal.increment();
    }

    public void recordAbortReason(String reason) {
        abortReasons.computeIfAbsent(reason,
                ignored -> new LongAdder()).increment();
    }

    public void recordLock() {
        lockTotal.increment();
    }

    public void recordLockResolve() {
        lockResolveTotal.increment();
    }

    public void setLongRunning(long count) {
        longRunning.set(count);
    }

    public void setLockCount(long count) {
        lockCount.set(count);
    }

    public Snapshot snapshot() {
        long count = commits.sum();
        double avgMs = count == 0 ? 0
                : commitLatencyNanos.sum() / (double) count / 1_000_000.0;
        return new Snapshot(begins.sum(), active.get(), commits.sum(),
                rollbacks.sum(), conflicts.sum(), aborts.sum(),
                recoveries.sum(), reads.sum(), lockCount.get(), avgMs,
                prepareAvgMs(), networkRetries.sum(), lockWaitAvgMs(),
                regionCount.get(), recoveryTimeAvgMs(), expiredTotal.sum(),
                lockTotal.sum(), lockResolveTotal.sum(), longRunning.get(),
                Map.copyOf(abortReasons));
    }

    private double prepareAvgMs() {
        long count = begins.sum();
        return count == 0 ? 0
                : prepareLatencyNanos.sum() / (double) count / 1_000_000.0;
    }

    private double lockWaitAvgMs() {
        long count = begins.sum();
        return count == 0 ? 0
                : lockWaitNanos.sum() / (double) count / 1_000_000.0;
    }

    private double recoveryTimeAvgMs() {
        long count = recoveries.sum();
        return count == 0 ? 0
                : recoveryTimeNanos.sum() / (double) count / 1_000_000.0;
    }

    public String sectionText() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "# Transaction\r\n"
                        + "begin_txn:%d\r\n"
                        + "active_txn:%d\r\n"
                        + "committed_txn:%d\r\n"
                        + "rollback_txn:%d\r\n"
                        + "conflict_txn:%d\r\n"
                        + "abort_txn:%d\r\n"
                        + "recovery_txn:%d\r\n"
                        + "read_txn:%d\r\n"
                        + "lock_count:%d\r\n"
                        + "txn_commit_latency_ms:%.3f\r\n"
                        + "txn_prepare_latency_ms:%.3f\r\n"
                        + "txn_network_retry:%d\r\n"
                        + "txn_lock_wait_ms:%.3f\r\n"
                        + "txn_region_count:%d\r\n"
                        + "txn_recovery_time_ms:%.3f\r\n"
                        + "txn_expired_total:%d\r\n"
                        + "txn_long_running:%d\r\n"
                        + "lock_total:%d\r\n"
                        + "lock_resolve_total:%d\r\n"
                        + "lock_wait_seconds:%.3f\r\n",
                s.beginTotal(), s.activeTxn(), s.committedTxn(), s.rollbackTxn(),
                s.conflictTxn(), s.abortTxn(), s.recoveryTxn(), s.readTxn(),
                s.lockCount(), s.commitLatencyMs(), s.prepareLatencyMs(),
                s.networkRetry(), s.lockWaitMs(), s.regionCount(),
                s.recoveryTimeMs(), s.expiredTotal(), s.longRunning(),
                s.lockTotal(), s.lockResolveTotal(),
                s.lockWaitMs() / 1000.0);
    }

    public record Snapshot(long beginTotal, long activeTxn, long committedTxn,
                           long rollbackTxn, long conflictTxn, long abortTxn,
                           long recoveryTxn, long readTxn,
                           long lockCount, double commitLatencyMs,
                           double prepareLatencyMs, long networkRetry,
                           double lockWaitMs, long regionCount,
                           double recoveryTimeMs, long expiredTotal,
                           long lockTotal, long lockResolveTotal,
                           long longRunning,
                           Map<String, LongAdder> abortReasons) {
    }
}
