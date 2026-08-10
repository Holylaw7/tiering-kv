package io.tieringkv.storage.cache;

import java.util.Arrays;

/** 淘汰候选（ADR-0010）：由策略选出，EvictionManager 校验存活后执行迁移。 */
public record EvictionCandidate(
        byte[] key,
        long frequency,
        long lastAccessTime,
        int sizeBytes,
        double score) {

    @Override
    public boolean equals(Object other) {
        return other instanceof EvictionCandidate that
                && Arrays.equals(key, that.key)
                && frequency == that.frequency
                && lastAccessTime == that.lastAccessTime
                && sizeBytes == that.sizeBytes
                && Double.compare(score, that.score) == 0;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Long.hashCode(frequency);
        result = 31 * result + Long.hashCode(lastAccessTime);
        result = 31 * result + sizeBytes;
        result = 31 * result + Double.hashCode(score);
        return result;
    }
}
