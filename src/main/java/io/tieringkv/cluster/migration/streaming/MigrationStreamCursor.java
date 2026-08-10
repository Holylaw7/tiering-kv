package io.tieringkv.cluster.migration.streaming;

import java.util.Arrays;

/** 流式迁移游标（ADR-0053）：slotId/lastKey/lastVersion/offset/checksum。 */
public record MigrationStreamCursor(
        int slotId,
        byte[] lastKey,
        long lastVersion,
        long offset,
        long checksum) {

    public static MigrationStreamCursor empty(int slotId) {
        return new MigrationStreamCursor(slotId, new byte[0], -1, 0, 0);
    }

    public MigrationStreamCursor {
        lastKey = lastKey == null ? new byte[0] : lastKey.clone();
    }

    @Override
    public byte[] lastKey() {
        return lastKey.clone();
    }

    public MigrationStreamCursor advance(byte[] key, long version, long updatedChecksum) {
        return new MigrationStreamCursor(slotId, key, version, offset + 1, updatedChecksum);
    }
}
