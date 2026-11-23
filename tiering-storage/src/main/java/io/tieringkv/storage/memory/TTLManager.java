package io.tieringkv.storage.memory;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 主动过期调度（ADR-0009 混合策略）。
 * min-heap 按 (expireMillis, version) 排序；带版本守卫，避免误删被重新设置
 * TTL 的键。生产环境由 daemon 后台线程周期触发；测试可手动调用
 * {@link #expireOnce()}。
 */
public final class TTLManager {

    private record ScheduledExpiry(long expireMillis, long version, int segmentIndex, byte[] key) {
    }

    private final PriorityQueue<ScheduledExpiry> queue = new PriorityQueue<>(
            Comparator.comparingLong(ScheduledExpiry::expireMillis)
                    .thenComparingLong(ScheduledExpiry::version));
    private final MemTable memTable;
    private final ScheduledExecutorService scheduler;
    private final long intervalMillis;

    public TTLManager(MemTable memTable, ScheduledExecutorService scheduler, long intervalMillis) {
        this.memTable = memTable;
        this.scheduler = scheduler;
        this.intervalMillis = intervalMillis;
    }

    public void start() {
        if (scheduler != null) {
            scheduler.scheduleWithFixedDelay(
                    this::expireOnce, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void schedule(long expireMillis, long version, int segmentIndex, byte[] key) {
        synchronized (queue) {
            queue.offer(new ScheduledExpiry(expireMillis, version, segmentIndex, key.clone()));
        }
    }

    /** 清扫到当前时刻为止已到期的条目，返回实际移除数量。 */
    public long expireOnce() {
        long now = memTable.nowMillis();
        long removed = 0;
        while (true) {
            ScheduledExpiry next;
            synchronized (queue) {
                next = queue.peek();
                if (next == null || next.expireMillis() > now) {
                    break;
                }
                queue.poll();
            }
            if (memTable.expireIfMatches(next.segmentIndex(), next.key(), next.version(), next.expireMillis())) {
                removed++;
            }
        }
        return removed;
    }
}
