package io.tieringkv.cluster.raft;

/** TimeoutNow（ADR-0064）：请求 follower 立即发起选举。 */
public record TimeoutNowRequest(long term, String leaderId) {
}
