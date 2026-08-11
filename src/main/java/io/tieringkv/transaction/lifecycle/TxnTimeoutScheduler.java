package io.tieringkv.transaction.lifecycle;

import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.transaction.router.DistributedTxnRouter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 超时调度（ADR-0088）：长事务自动 abort，禁止永久锁。 */
public final class TxnTimeoutScheduler implements AutoCloseable {

    private final TransactionLifecycleManager lifecycle;
    private final DistributedTxnRouter router;
    private final TransactionMetricsRegistry metrics;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();

    public TxnTimeoutScheduler(TransactionLifecycleManager lifecycle,
                               DistributedTxnRouter router,
                               TransactionMetricsRegistry metrics) {
        this.lifecycle = lifecycle;
        this.router = router;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "txn-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start(long intervalMillis) {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(this::scan, intervalMillis,
                    intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public int scan() {
        int aborted = 0;
        for (TransactionLifecycleManager.TxnHandle handle
                : lifecycle.expiredCandidates(System.currentTimeMillis())) {
            try {
                router.rollback(handle.txn());
                lifecycle.markExpired(handle.txn().txnId());
                if (metrics != null) {
                    metrics.recordExpired();
                    metrics.recordAbortReason("expired");
                }
                aborted++;
            } catch (RuntimeException ignored) {
                // 回滚失败由 LockResolver/恢复兜底
            }
        }
        return aborted;
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
    }
}
