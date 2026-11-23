package io.tieringkv.cluster.raft;

/** 快照安装响应（ADR-0040）。 */
public record InstallSnapshotResponse(long term, boolean success) {
}
