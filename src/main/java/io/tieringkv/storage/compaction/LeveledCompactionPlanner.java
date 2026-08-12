package io.tieringkv.storage.compaction;

/** Leveled Compaction 计划（ADR-0204）：L0→L1→L2 层级。 */
public final class LeveledCompactionPlanner {

    /** 层级计划：当前层 + 目标层 + 文件数。 */
    public record CompactionPlan(int sourceLevel,
                                 int targetLevel, int fileCount) {
    }

    /** 当前层是否需要合并（总量超过上限）。 */
    public boolean shouldCompact(long totalBytes, long maxBytes) {
        if (totalBytes < 0 || maxBytes < 0) {
            throw new IllegalArgumentException(
                    "sizes must be non-negative");
        }
        return totalBytes > maxBytes;
    }

    /** 生成合并计划：文件数 = ceil(总量/单文件上限)。 */
    public CompactionPlan planLevel(long totalBytes, long maxBytes,
                                    long fileMaxBytes, int level) {
        if (totalBytes < 0 || maxBytes < 0 || fileMaxBytes <= 0
                || level < 0) {
            throw new IllegalArgumentException(
                    "invalid plan inputs");
        }
        if (!shouldCompact(totalBytes, maxBytes)) {
            return new CompactionPlan(level, level + 1, 0);
        }
        int files = (int) Math.ceil(
                (double) totalBytes / fileMaxBytes);
        return new CompactionPlan(level, level + 1,
                Math.max(1, files));
    }
}
