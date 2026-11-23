package io.tieringkv.distributed.harness;

import java.util.concurrent.ConcurrentHashMap;

/** 内存验证存储（原 harness 语义）。 */
public final class MemoryVerificationStore
        implements VerificationStore {

    private final ConcurrentHashMap<String, String> values =
            new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
        return values.get(key);
    }

    @Override
    public void put(String key, String value) {
        values.put(key, value);
    }
}
