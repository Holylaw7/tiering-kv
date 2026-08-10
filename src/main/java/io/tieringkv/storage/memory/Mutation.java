package io.tieringkv.storage.memory;

import java.util.Arrays;

/** 批量变更（ADR-0048）：PUT / DELETE。 */
public record Mutation(
        Type type,
        byte[] key,
        byte[] value,
        long ttlMillis) {

    public enum Type {
        PUT,
        DELETE
    }

    public Mutation {
        key = key.clone();
        if (value != null) {
            value = value.clone();
        }
    }

    public static Mutation put(byte[] key, byte[] value) {
        return new Mutation(Type.PUT, key, value, -1);
    }

    public static Mutation put(byte[] key, byte[] value, long ttlMillis) {
        return new Mutation(Type.PUT, key, value, ttlMillis);
    }

    public static Mutation delete(byte[] key) {
        return new Mutation(Type.DELETE, key, null, -1);
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }
}
