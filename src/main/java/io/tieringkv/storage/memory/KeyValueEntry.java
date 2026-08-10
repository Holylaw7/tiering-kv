package io.tieringkv.storage.memory;

import java.util.Arrays;

/**
 * 键值条目（ADR-0007）。字段覆盖未来 WAL / Snapshot / LSM Flush / Compaction 需要：
 * 创建/更新时间、过期时间、全局版本、tombstone 标记与字节估算。
 */
public record KeyValueEntry(
        byte[] key,
        byte[] value,
        long createTimestamp,
        long updateTimestamp,
        long expireTimestamp,
        long version,
        boolean deleted,
        int size) {

    /** 固定开销估算：对象头 + 数组头 + 时间戳/版本字段（约数，不追求精确）。 */
    private static final int OVERHEAD_BYTES = 64;

    public KeyValueEntry {
        key = key.clone();
        if (value != null) {
            value = value.clone();
        }
    }

    /** 新建存活 entry；ttlMillis &lt;= 0 表示永不过期。 */
    public static KeyValueEntry live(byte[] key, byte[] value, long nowMillis, long ttlMillis, long version) {
        long expireTimestamp = ttlMillis > 0 ? nowMillis + ttlMillis : -1;
        return new KeyValueEntry(key, value, nowMillis, nowMillis, expireTimestamp, version, false,
                sizeOf(key, value));
    }

    /** tombstone：保留键位但不物理删除（为 WAL / Snapshot / LSM 准备）。 */
    public static KeyValueEntry tombstone(byte[] key, long nowMillis, long version) {
        return new KeyValueEntry(key, null, nowMillis, nowMillis, -1, version, true, sizeOf(key, null));
    }

    /** 存活 = 未删除且（无过期时间或未到过期时间）。 */
    public boolean isLive(long nowMillis) {
        return !deleted && (expireTimestamp < 0 || nowMillis < expireTimestamp);
    }

    public boolean isExpired(long nowMillis) {
        return expireTimestamp >= 0 && nowMillis >= expireTimestamp;
    }

    public static int sizeOf(byte[] key, byte[] value) {
        return key.length + (value == null ? 0 : value.length) + OVERHEAD_BYTES;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeyValueEntry that
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value)
                && createTimestamp == that.createTimestamp
                && updateTimestamp == that.updateTimestamp
                && expireTimestamp == that.expireTimestamp
                && version == that.version
                && deleted == that.deleted
                && size == that.size;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + Long.hashCode(createTimestamp);
        result = 31 * result + Long.hashCode(updateTimestamp);
        result = 31 * result + Long.hashCode(expireTimestamp);
        result = 31 * result + Long.hashCode(version);
        result = 31 * result + Boolean.hashCode(deleted);
        result = 31 * result + size;
        return result;
    }
}
