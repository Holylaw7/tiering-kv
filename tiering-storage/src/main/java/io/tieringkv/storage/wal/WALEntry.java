package io.tieringkv.storage.wal;

import java.util.Arrays;
import java.util.Objects;

/**
 * WAL 逻辑记录（ADR-0015）：PUT 携带 value 与 ttl（相对毫秒）；
 * DELETE 携带 tombstone 语义（value 为 null）。
 */
public record WALEntry(
        Operation operation,
        long timestamp,
        byte[] key,
        byte[] value,
        long ttlMillis,
        long version) {

    public WALEntry {
        key = key.clone();
        if (value != null) {
            value = value.clone();
        }
    }

    public static WALEntry put(long timestamp, byte[] key, byte[] value, long ttlMillis, long version) {
        return new WALEntry(Operation.PUT, timestamp, key, value, ttlMillis, version);
    }

    public static WALEntry delete(long timestamp, byte[] key, long version) {
        return new WALEntry(Operation.DELETE, timestamp, key, null, -1, version);
    }

    public enum Operation {
        PUT,
        DELETE
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WALEntry that
                && operation == that.operation
                && timestamp == that.timestamp
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value)
                && ttlMillis == that.ttlMillis
                && version == that.version;
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(operation, timestamp, ttlMillis, version);
        result = 31 * result + Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }
}
