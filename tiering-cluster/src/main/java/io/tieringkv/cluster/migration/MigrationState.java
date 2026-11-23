package io.tieringkv.cluster.migration;

/** 迁移状态机（ADR-0045）：INIT → COPYING ⇄ PAUSED → VERIFYING → SWITCHING → DONE。 */
public enum MigrationState {
    INIT,
    COPYING,
    PAUSED,
    VERIFYING,
    SWITCHING,
    DONE,
    FAILED
}
