package io.tieringkv.transaction.async;

/** Async Commit（ADR-0209）：单区一阶段提交 + 回退 2PC。 */
public final class AsyncCommitCoordinator {

    /** 提交结果。 */
    public record CommitResult(String txnId, boolean onePhase,
                               boolean succeeded) {
    }

    /** 单区事务：一阶段提交（无需 2PC）。 */
    public CommitResult commitOnePhase(String txnId,
                                       int regionCount) {
        validate(txnId);
        if (regionCount != 1) {
            return new CommitResult(txnId, false, false);
        }
        return new CommitResult(txnId, true, true);
    }

    /** 多区事务：回退两阶段提交（模拟决策成功）。 */
    public CommitResult commitTwoPhase(String txnId,
                                       int regionCount) {
        validate(txnId);
        if (regionCount < 1) {
            throw new IllegalArgumentException(
                    "region count must be positive");
        }
        return new CommitResult(txnId, false, true);
    }

    /** 自动选择：单区一阶段，多区两阶段。 */
    public CommitResult commit(String txnId, int regionCount) {
        return regionCount == 1
                ? commitOnePhase(txnId, regionCount)
                : commitTwoPhase(txnId, regionCount);
    }

    private static void validate(String txnId) {
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException(
                    "txnId required");
        }
    }
}
