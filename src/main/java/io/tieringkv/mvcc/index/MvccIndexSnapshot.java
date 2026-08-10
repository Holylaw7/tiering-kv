package io.tieringkv.mvcc.index;

import io.tieringkv.mvcc.MvccEntry;

import java.util.List;

/** MVCC 索引快照（ADR-0080）：版本列表 + 元数据。 */
public record MvccIndexSnapshot(List<MvccEntry> versions,
                                long maxCommitTS,
                                long createdAtMillis) {

    public static MvccIndexSnapshot of(List<MvccEntry> versions) {
        long max = Long.MIN_VALUE;
        for (MvccEntry version : versions) {
            max = Math.max(max, version.commitTS());
        }
        return new MvccIndexSnapshot(List.copyOf(versions), max,
                System.currentTimeMillis());
    }
}
