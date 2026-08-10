package io.tieringkv.cluster.lifecycle.split;

import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.storage.StorageEngine;

/** 分裂任务（ADR-0061）：五阶段状态机。 */
public final class RegionSplitTask {

    public enum SplitPhase {
        PREPARE,
        SNAPSHOT,
        INSTALL,
        COMMIT,
        CLEANUP
    }

    private final RegionId regionId;
    private final byte[] splitKey;
    private final StorageEngine source;
    private final StorageEngine leftStorage;
    private final StorageEngine rightStorage;
    private final SplitWriteBuffer writeBuffer = new SplitWriteBuffer();
    private SplitPhase phase = SplitPhase.PREPARE;
    private SplitSnapshot snapshot;

    public RegionSplitTask(RegionId regionId, byte[] splitKey,
                           StorageEngine source,
                           StorageEngine leftStorage,
                           StorageEngine rightStorage) {
        this.regionId = regionId;
        this.splitKey = splitKey.clone();
        this.source = source;
        this.leftStorage = leftStorage;
        this.rightStorage = rightStorage;
    }

    public RegionId regionId() {
        return regionId;
    }

    public byte[] splitKey() {
        return splitKey.clone();
    }

    public StorageEngine source() {
        return source;
    }

    public StorageEngine leftStorage() {
        return leftStorage;
    }

    public StorageEngine rightStorage() {
        return rightStorage;
    }

    public SplitWriteBuffer writeBuffer() {
        return writeBuffer;
    }

    public synchronized SplitPhase phase() {
        return phase;
    }

    public synchronized void phase(SplitPhase next) {
        this.phase = next;
    }

    public synchronized SplitSnapshot snapshot() {
        return snapshot;
    }

    public synchronized void snapshot(SplitSnapshot snapshot) {
        this.snapshot = snapshot;
    }
}
