package io.tieringkv.backup.pitr;

/** PITR 保留策略（ADR-0111）：段数量/时间上限 + 安全水位。 */
public record RetentionPolicy(int maxSegments, long maxAgeMillis,
                              long minSafeWatermark) {

    public RetentionPolicy {
        if (maxSegments < 1) {
            throw new IllegalArgumentException(
                    "maxSegments must be >= 1");
        }
    }

    public boolean shouldRetain(long segmentIndex, long segmentAgeMillis,
                                long segmentMinSeq) {
        if (segmentMinSeq <= minSafeWatermark) {
            return true; // 不得删除仍被 checkpoint 依赖的段
        }
        if (maxAgeMillis > 0 && segmentAgeMillis <= maxAgeMillis) {
            return true;
        }
        return segmentIndex <= maxSegments;
    }
}
