package io.tieringkv.cluster.lifecycle.merge;

import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.storage.StorageEngine;

/** 合并任务（ADR-0062）：PREPARE → LOCK → TRANSFER → UPDATE_META → TOMBSTONE。 */
public final class MergeTask {

    public enum MergePhase {
        PREPARE,
        LOCK,
        TRANSFER,
        UPDATE_META,
        TOMBSTONE
    }

    private final RegionId leftId;
    private final RegionId rightId;
    private final StorageEngine leftStorage;
    private final StorageEngine rightStorage;
    private MergePhase phase = MergePhase.PREPARE;

    public MergeTask(RegionId leftId, RegionId rightId,
                     StorageEngine leftStorage, StorageEngine rightStorage) {
        this.leftId = leftId;
        this.rightId = rightId;
        this.leftStorage = leftStorage;
        this.rightStorage = rightStorage;
    }

    public RegionId leftId() {
        return leftId;
    }

    public RegionId rightId() {
        return rightId;
    }

    /** 合并后的 region id（与 RegionManager.mergeRegion 派生规则一致）。 */
    public RegionId mergedId() {
        return new RegionId(leftId.id() * 10 + 3);
    }

    public StorageEngine leftStorage() {
        return leftStorage;
    }

    public StorageEngine rightStorage() {
        return rightStorage;
    }

    public synchronized MergePhase phase() {
        return phase;
    }

    public synchronized void phase(MergePhase next) {
        this.phase = next;
    }
}
