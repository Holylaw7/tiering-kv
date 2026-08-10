package io.tieringkv.cluster.raft;

/** 投票请求（ADR-0038）。 */
public record VoteRequest(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
}
