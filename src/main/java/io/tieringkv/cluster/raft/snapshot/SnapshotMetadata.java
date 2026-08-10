package io.tieringkv.cluster.raft.snapshot;

/** 快照元数据（ADR-0040）：最后包含的日志索引与任期。 */
public record SnapshotMetadata(long lastIncludedIndex, long lastIncludedTerm) {
}
