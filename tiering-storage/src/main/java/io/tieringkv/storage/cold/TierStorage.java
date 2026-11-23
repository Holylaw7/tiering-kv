package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;

/** 分层存储接口（ADR-0017）：冷层实现（MemTable 经 StorageEngine 统一）。 */
public interface TierStorage {

    byte[] get(byte[] key);

    void put(KeyValueEntry entry);

    void delete(byte[] key);
}
