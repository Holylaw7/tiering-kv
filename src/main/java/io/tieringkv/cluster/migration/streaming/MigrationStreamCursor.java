package io.tieringkv.cluster.migration.streaming;

import java.util.Arrays;

/**
 * 流式迁移游标（ADR-0053）：slotId/lastKey/lastVersion/offset/checksum。
 * 原地更新：迁移热路径不产生每条目分配/克隆。
 */
public final class MigrationStreamCursor {

    private final int slotId;
    private byte[] lastKey;
    private long lastVersion;
    private long offset;
    private long checksum;

    public MigrationStreamCursor(int slotId, byte[] lastKey,
                                 long lastVersion, long offset, long checksum) {
        this.slotId = slotId;
        this.lastKey = lastKey == null ? new byte[0] : lastKey.clone();
        this.lastVersion = lastVersion;
        this.offset = offset;
        this.checksum = checksum;
    }

    public static MigrationStreamCursor empty(int slotId) {
        return new MigrationStreamCursor(slotId, new byte[0], -1, 0, 0);
    }

    public int slotId() {
        return slotId;
    }

    public byte[] lastKey() {
        return lastKey.clone();
    }

    public long lastVersion() {
        return lastVersion;
    }

    public long offset() {
        return offset;
    }

    public long checksum() {
        return checksum;
    }

    /** 原地前进（热路径：无分配）。 */
    public MigrationStreamCursor advance(byte[] key, long version, long updatedChecksum) {
        this.lastKey = key == null ? new byte[0] : key.clone();
        this.lastVersion = version;
        this.offset++;
        this.checksum = updatedChecksum;
        return this;
    }

    @Override
    public String toString() {
        return "MigrationStreamCursor(slotId=" + slotId
                + ", lastKey=" + Arrays.toString(lastKey)
                + ", lastVersion=" + lastVersion
                + ", offset=" + offset
                + ", checksum=" + checksum + ")";
    }
}
