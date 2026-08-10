package io.tieringkv.cluster.raft;

/** 日志复制响应（ADR-0037）。 */
public record AppendEntriesResponse(long term, boolean success, long matchIndex) {
}
