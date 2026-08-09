package io.tieringkv.command;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 占位实现：ConcurrentHashMap + ByteBuffer 键，线程安全。
 * Phase 2 由分段 MemTable 替换（ADR-0003）。
 */
public final class InMemoryKVStore implements KVStore {

    private final ConcurrentHashMap<ByteBuffer, byte[]> map = new ConcurrentHashMap<>();

    @Override
    public byte[] get(byte[] key) {
        return map.get(ByteBuffer.wrap(key));
    }

    @Override
    public void put(byte[] key, byte[] value) {
        // 防御性拷贝：避免调用方后续修改影响已存储数据
        map.put(ByteBuffer.wrap(key.clone()), value.clone());
    }

    @Override
    public long delete(byte[] key) {
        return map.remove(ByteBuffer.wrap(key)) != null ? 1 : 0;
    }

    @Override
    public boolean exists(byte[] key) {
        return map.containsKey(ByteBuffer.wrap(key));
    }
}
