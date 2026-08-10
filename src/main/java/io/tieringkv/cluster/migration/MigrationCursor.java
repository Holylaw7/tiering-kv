package io.tieringkv.cluster.migration;

import java.util.Arrays;

/**
 * 迁移游标（ADR-0045）：lastKey / lastVersion / checkpointOffset，
 * 支持暂停、恢复与崩溃续传。
 */
public record MigrationCursor(
        byte[] lastKey,
        long lastVersion,
        long checkpointOffset) {

    public static MigrationCursor empty() {
        return new MigrationCursor(new byte[0], -1, 0);
    }

    public MigrationCursor {
        lastKey = lastKey == null ? new byte[0] : lastKey.clone();
    }

    @Override
    public byte[] lastKey() {
        return lastKey.clone();
    }

    public MigrationCursor advance(byte[] key, long version) {
        return new MigrationCursor(key, version, checkpointOffset + 1);
    }
}
