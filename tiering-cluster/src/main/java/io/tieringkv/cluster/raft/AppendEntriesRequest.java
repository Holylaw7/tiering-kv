package io.tieringkv.cluster.raft;

import java.util.List;

/** 日志复制/心跳请求（ADR-0037）。 */
public record AppendEntriesRequest(
        long term,
        String leaderId,
        long prevLogIndex,
        long prevLogTerm,
        List<LogEntry> entries,
        long leaderCommit) {

    public AppendEntriesRequest {
        entries = List.copyOf(entries);
    }

    public boolean heartbeat() {
        return entries.isEmpty();
    }
}
