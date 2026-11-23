package io.tieringkv.storage.tiering;

import io.tieringkv.storage.memory.KeyValueEntry;

/** 迁移任务（ADR-0022）：key/value/version + source/target + 重试与状态。 */
public record MigrationTask(
        KeyValueEntry entry,
        String source,
        String target,
        int retryCount,
        Status status) {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCESS,
        FAILED,
        RETRY
    }

    public static MigrationTask pending(KeyValueEntry entry, String source, String target) {
        return new MigrationTask(entry, source, target, 0, Status.PENDING);
    }

    public MigrationTask withStatus(Status newStatus) {
        return new MigrationTask(entry, source, target, retryCount, newStatus);
    }

    public MigrationTask withRetryCount(int count) {
        return new MigrationTask(entry, source, target, count, status);
    }

    public byte[] key() {
        return entry.key();
    }

    public long version() {
        return entry.version();
    }
}
