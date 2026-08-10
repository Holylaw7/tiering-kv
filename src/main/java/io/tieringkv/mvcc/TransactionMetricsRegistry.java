package io.tieringkv.mvcc;

import java.util.Locale;
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
    }

    public void recordRecovery() {
        recoveries.increment();
    }

    public void recordRead() {
        reads.increment();
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
                recoveries.sum(), reads.sum(), lockCount.get(), avgMs);
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
                        + "txn_commit_latency_ms:%.3f\r\n",
                s.beginTotal(), s.activeTxn(), s.committedTxn(), s.rollbackTxn(),
                s.conflictTxn(), s.abortTxn(), s.recoveryTxn(), s.readTxn(),
                s.lockCount(), s.commitLatencyMs());
    }

    public record Snapshot(long beginTotal, long activeTxn, long committedTxn,
                           long rollbackTxn, long conflictTxn, long abortTxn,
                           long recoveryTxn, long readTxn,
                           long lockCount, double commitLatencyMs) {
    }
}
