package io.tieringkv.storage.tiering;

/** 分层压力状态（ADR-0021）。 */
public enum TierState {
    NORMAL,
    WARNING,
    CRITICAL
}
