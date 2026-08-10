package io.tieringkv.cluster.migration;

/** 迁移状态机（ADR-0043）：INIT → COPYING → VERIFYING → SWITCHING → DONE。 */
public enum MigrationState {
    INIT,
    COPYING,
    VERIFYING,
    SWITCHING,
    DONE,
    FAILED
}
