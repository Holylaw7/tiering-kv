package io.tieringkv.cluster.raft;

import java.util.Arrays;

/** 快照安装请求（ADR-0040）：term + 最后包含索引/任期 + 状态数据。 */
public record InstallSnapshotRequest(
        long term,
        String leaderId,
        long lastIncludedIndex,
        long lastIncludedTerm,
        byte[] data) {

    public InstallSnapshotRequest {
        data = data.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof InstallSnapshotRequest that
                && term == that.term
                && leaderId.equals(that.leaderId)
                && lastIncludedIndex == that.lastIncludedIndex
                && lastIncludedTerm == that.lastIncludedTerm
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * Long.hashCode(term)
                + leaderId.hashCode())
                + Long.hashCode(lastIncludedIndex))
                + Long.hashCode(lastIncludedTerm))
                + Arrays.hashCode(data);
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
