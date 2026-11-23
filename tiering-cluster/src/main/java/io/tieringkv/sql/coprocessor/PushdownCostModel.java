package io.tieringkv.sql.coprocessor;

/**
 * 下推成本模型（ADR-0229）：估算下推收益（本地扫描字节 vs 传输成本），
 * 供 SqlExecutor 选择下推计划。
 */
public final class PushdownCostModel {

    /** 下推决策。 */
    public record PushdownDecision(boolean pushdown,
                                   long localBytes,
                                   long transferBytes,
                                   String reason) {
    }

    private final long fixedOverheadBytes;

    public PushdownCostModel(long fixedOverheadBytes) {
        if (fixedOverheadBytes < 0) {
            throw new IllegalArgumentException(
                    "fixed overhead must be non-negative");
        }
        this.fixedOverheadBytes = fixedOverheadBytes;
    }

    /**
     * 估算：本地扫描 = rows × localBytesPerRow；
     * 传输 = rows × transferBytesPerRow + fixedOverhead；
     * 本地扫描 > 传输成本 → 下推。
     */
    public PushdownDecision shouldPushdown(
            long rows, long localBytesPerRow,
            long transferBytesPerRow) {
        if (rows < 0 || localBytesPerRow < 0
                || transferBytesPerRow < 0) {
            throw new IllegalArgumentException(
                    "costs must be non-negative");
        }
        long localBytes = rows * localBytesPerRow;
        long transferBytes = rows * transferBytesPerRow
                + fixedOverheadBytes;
        boolean pushdown = localBytes > transferBytes;
        return new PushdownDecision(pushdown, localBytes,
                transferBytes,
                pushdown ? "local scan cheaper than transfer"
                        : "transfer cheaper than local scan");
    }
}
