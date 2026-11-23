package io.tieringkv.cluster.region;

/** Region 生命周期状态（ADR-0057）。 */
public enum RegionState {
    NORMAL,
    SPLITTING,
    MERGING,
    TOMBSTONE
}
