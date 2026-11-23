package io.tieringkv.cluster.raft;

/** 投票响应（ADR-0038）。 */
public record VoteResponse(long term, boolean granted) {
}
