package io.tieringkv.mvcc;

/** GC 安全点（ADR-0075）：低于该点且非最新的版本可回收。 */
public record SafePoint(long timestamp) {

    public static final SafePoint NONE = new SafePoint(Long.MIN_VALUE);

    public boolean canCollect(long commitTS) {
        return commitTS < timestamp;
    }
}
