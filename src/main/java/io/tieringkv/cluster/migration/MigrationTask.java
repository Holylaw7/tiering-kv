package io.tieringkv.cluster.migration;

import io.tieringkv.storage.StorageEngine;

/** 迁移任务（ADR-0043）：slot 范围 + 源/目标存储 + 目标分片。 */
public final class MigrationTask {

    private final String taskId;
    private final int slotStart;
    private final int slotEnd;
    private final int targetShardId;
    private final StorageEngine source;
    private final StorageEngine target;
    private final Object lock = new Object();
    private MigrationState state = MigrationState.INIT;
    private MigrationCheckpoint checkpoint = MigrationCheckpoint.empty();

    public MigrationTask(String taskId, int slotStart, int slotEnd, int targetShardId,
                         StorageEngine source, StorageEngine target) {
        this.taskId = taskId;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.targetShardId = targetShardId;
        this.source = source;
        this.target = target;
    }

    public String taskId() {
        return taskId;
    }

    public int slotStart() {
        return slotStart;
    }

    public int slotEnd() {
        return slotEnd;
    }

    public int targetShardId() {
        return targetShardId;
    }

    public StorageEngine source() {
        return source;
    }

    public StorageEngine target() {
        return target;
    }

    public Object lock() {
        return lock;
    }

    public MigrationState state() {
        synchronized (lock) {
            return state;
        }
    }

    public void state(MigrationState state) {
        synchronized (lock) {
            this.state = state;
        }
    }

    public MigrationCheckpoint checkpoint() {
        synchronized (lock) {
            return checkpoint;
        }
    }

    public void checkpoint(MigrationCheckpoint checkpoint) {
        synchronized (lock) {
            this.checkpoint = checkpoint;
        }
    }
}
