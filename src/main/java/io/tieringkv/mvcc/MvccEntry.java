package io.tieringkv.mvcc;

import java.util.Arrays;

/** MVCC 条目（ADR-0071）。 */
public final class MvccEntry {

    private final byte[] key;
    private final byte[] value;
    private final long startTS;
    private final long commitTS;
    private final WriteType writeType;

    public MvccEntry(byte[] key, byte[] value, long startTS,
                     long commitTS, WriteType writeType) {
        this.key = key.clone();
        this.value = value == null ? null : value.clone();
        this.startTS = startTS;
        this.commitTS = commitTS;
        this.writeType = writeType;
    }

    public byte[] key() {
        return key.clone();
    }

    /** 零拷贝内部访问（GC/索引持久化专用）；调用方禁止修改返回数组。 */
    public byte[] keyBytes() {
        return key;
    }

    public byte[] value() {
        return value == null ? null : value.clone();
    }

    /** 零拷贝内部访问（GC/索引持久化专用）；调用方禁止修改返回数组。 */
    public byte[] valueBytes() {
        return value;
    }

    public long startTS() {
        return startTS;
    }

    public long commitTS() {
        return commitTS;
    }

    public WriteType writeType() {
        return writeType;
    }

    public boolean isVisible() {
        return writeType != WriteType.LOCK;
    }

    public boolean isDelete() {
        return writeType == WriteType.DELETE;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MvccEntry that
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value)
                && startTS == that.startTS
                && commitTS == that.commitTS
                && writeType == that.writeType;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + Long.hashCode(startTS);
        result = 31 * result + Long.hashCode(commitTS);
        result = 31 * result + writeType.hashCode();
        return result;
    }
}
