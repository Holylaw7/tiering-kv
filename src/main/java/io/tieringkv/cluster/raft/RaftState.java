package io.tieringkv.cluster.raft;

/** Raft 角色（ADR-0037/0038）。 */
public enum RaftState {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
