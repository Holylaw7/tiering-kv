package io.tieringkv.cluster.lifecycle;

/** Region 生命周期状态（ADR-0061/0062）。 */
public enum RegionLifecycleState {
    NORMAL,
    SPLITTING,
    SPLIT_READY,
    MERGING,
    MERGE_READY,
    TOMBSTONE
}
