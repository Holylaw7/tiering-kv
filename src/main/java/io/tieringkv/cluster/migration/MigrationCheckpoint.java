package io.tieringkv.cluster.migration;

import java.util.Arrays;

/** 迁移检查点（ADR-0043）：最后复制 key + 计数 + 字节 + 校验和。 */
public record MigrationCheckpoint(
        byte[] lastKey,
        long copiedEntries,
        long copiedBytes,
        long checksum,
        MigrationState state) {

    public static MigrationCheckpoint empty() {
        return new MigrationCheckpoint(new byte[0], 0, 0, 0, MigrationState.INIT);
    }

    public MigrationCheckpoint {
        lastKey = lastKey == null ? new byte[0] : lastKey.clone();
    }

    @Override
    public byte[] lastKey() {
        return lastKey.clone();
    }

    public MigrationCheckpoint withState(MigrationState state) {
        return new MigrationCheckpoint(lastKey, copiedEntries, copiedBytes, checksum, state);
    }
}
