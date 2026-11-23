package io.tieringkv.sql.coprocessor;

/**
 * 动态下推规划器（ADR-0236）：历史执行统计（EWMA 传输成本）→ 运行时
 * 下推决策，供 SqlExecutor 选择计划。
 */
public final class DynamicPushdownPlanner {

    /** 动态决策。 */
    public record DynamicDecision(boolean pushdown,
                                  double ewmaTransferPerRow,
                                  String reason) {
    }

    private final double alpha;
    private final long minRows;
    private volatile double ewmaTransferPerRow;

    public DynamicPushdownPlanner(double alpha, long minRows) {
        if (alpha <= 0 || alpha > 1 || minRows < 1) {
            throw new IllegalArgumentException(
                    "alpha in (0,1] and minRows >= 1 required");
        }
        this.alpha = alpha;
        this.minRows = minRows;
    }

    /** 记录一次执行统计：更新 EWMA 每行传输成本。 */
    public synchronized void record(long rows,
                                    long transferBytes,
                                    long elapsedNanos) {
        if (rows <= 0 || transferBytes < 0 || elapsedNanos <= 0) {
            throw new IllegalArgumentException(
                    "rows/elapsed must be positive");
        }
        double observed = transferBytes / (double) rows;
        ewmaTransferPerRow = ewmaTransferPerRow == 0
                ? observed
                : alpha * observed
                + (1 - alpha) * ewmaTransferPerRow;
    }

    /** 动态决策：历史传输成本（无历史时用给定值）vs 本地扫描成本。 */
    public DynamicDecision shouldPushdown(
            long rows, long localBytesPerRow,
            long transferBytesPerRow) {
        if (rows < 0 || localBytesPerRow < 0
                || transferBytesPerRow < 0) {
            throw new IllegalArgumentException(
                    "costs must be non-negative");
        }
        double effective = ewmaTransferPerRow > 0
                ? ewmaTransferPerRow : transferBytesPerRow;
        boolean pushdown = rows >= minRows
                && localBytesPerRow > effective;
        return new DynamicDecision(pushdown, effective,
                pushdown ? "local scan cheaper than historical "
                        + "transfer"
                        : rows < minRows
                        ? "below min rows threshold"
                        : "historical transfer cheaper than "
                        + "local scan");
    }
}
