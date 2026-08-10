package io.tieringkv.storage.cache;

import java.util.Arrays;

/**
 * 访问事件（ADR-0010）：GET / PUT / DELETE 来自命令路径；
 * EVICT 由 EvictionManager 在淘汰删除后产生（ARC 据此维护 ghost）。
 */
public record AccessEvent(byte[] key, AccessOperation operation, long timestamp, int sizeBytes) {

    public AccessEvent {
        key = key.clone();
    }

    public enum AccessOperation {
        GET,
        PUT,
        DELETE,
        EVICT
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AccessEvent that
                && Arrays.equals(key, that.key)
                && operation == that.operation
                && timestamp == that.timestamp
                && sizeBytes == that.sizeBytes;
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + operation.hashCode();
        result = 31 * result + Long.hashCode(timestamp);
        result = 31 * result + sizeBytes;
        return result;
    }
}
