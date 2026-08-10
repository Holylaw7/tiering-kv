package io.tieringkv.storage.memory;

import java.util.Arrays;

/**
 * 键值条目（ADR-0007）。字段覆盖未来 WAL / Snapshot / LSM Flush / Compaction 需要：
 * 创建/更新时间、过期时间、全局版本、tombstone 标记与字节估算。
 */
public final class KeyValueEntry {

    /** 固定开销估算：对象头 + 数组头 + 时间戳/版本字段（约数，不追求精确）。 */
    private static final int OVERHEAD_BYTES = 64;

    private final byte[] key;
    private final byte[] value;
    private final long createTimestamp;
    private final long updateTimestamp;
    private final long expireTimestamp;
    private final long version;
    private final boolean deleted;
    private final int size;

    public KeyValueEntry(byte[] key, byte[] value,
                         long createTimestamp, long updateTimestamp,
                         long expireTimestamp, long version,
                         boolean deleted, int size) {
        key = key.clone();
        if (value != null) {
            value = value.clone();
        }
        this.key = key;
        this.value = value;
        this.createTimestamp = createTimestamp;
        this.updateTimestamp = updateTimestamp;
        this.expireTimestamp = expireTimestamp;
        this.version = version;
        this.deleted = deleted;
        this.size = size;
    }

    /** 所有权转移构造（ADR-0059）：不克隆；调用方保证转移后不再修改数组。 */
    private KeyValueEntry(byte[] key, byte[] value,
                          long createTimestamp, long updateTimestamp,
                          long expireTimestamp, long version,
                          boolean deleted, int size, boolean owned) {
        this.key = key;
        this.value = value;
        this.createTimestamp = createTimestamp;
        this.updateTimestamp = updateTimestamp;
        this.expireTimestamp = expireTimestamp;
        this.version = version;
        this.deleted = deleted;
        this.size = size;
    }

    /** 新建存活 entry；ttlMillis &lt;= 0 表示永不过期。 */
    public static KeyValueEntry live(byte[] key, byte[] value, long nowMillis, long ttlMillis, long version) {
        long expireTimestamp = ttlMillis > 0 ? nowMillis + ttlMillis : -1;
        return new KeyValueEntry(key, value, nowMillis, nowMillis, expireTimestamp, version, false,
                sizeOf(key, value));
    }

    /** 所有权转移版本（ADR-0059）：applyRawBatch 热路径使用，零拷贝。 */
    static KeyValueEntry liveOwned(byte[] key, byte[] value, long nowMillis,
                                   long ttlMillis, long version) {
        long expireTimestamp = ttlMillis > 0 ? nowMillis + ttlMillis : -1;
        return new KeyValueEntry(key, value, nowMillis, nowMillis,
                expireTimestamp, version, false, sizeOf(key, value), true);
    }

    /** tombstone：保留键位但不物理删除（为 WAL / Snapshot / LSM 准备）。 */
    public static KeyValueEntry tombstone(byte[] key, long nowMillis, long version) {
        return new KeyValueEntry(key, null, nowMillis, nowMillis, -1, version, true, sizeOf(key, null));
    }

    public byte[] key() {
        return key;
    }

    public byte[] value() {
        return value;
    }

    public long createTimestamp() {
        return createTimestamp;
    }

    public long updateTimestamp() {
        return updateTimestamp;
    }

    public long expireTimestamp() {
        return expireTimestamp;
    }

    public long version() {
        return version;
    }

    public boolean deleted() {
        return deleted;
    }

    public int size() {
        return size;
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

    @Override
    public String toString() {
        return "KeyValueEntry[key=" + Arrays.toString(key)
                + ", value=" + Arrays.toString(value)
                + ", createTimestamp=" + createTimestamp
                + ", updateTimestamp=" + updateTimestamp
                + ", expireTimestamp=" + expireTimestamp
                + ", version=" + version
                + ", deleted=" + deleted
                + ", size=" + size + "]";
    }
}
