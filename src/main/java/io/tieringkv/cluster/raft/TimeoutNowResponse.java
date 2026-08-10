package io.tieringkv.cluster.raft;

/** TimeoutNow 响应（ADR-0064）。 */
public record TimeoutNowResponse(long term, boolean accepted) {
}
