package io.tieringkv.command;

/**
 * 键值存储抽象（Phase 1 最小接口；Phase 2 由 MemTable 实现并扩展 TTL / 配额）。
 * 键与值均为二进制安全。
 */
public interface KVStore {

    byte[] get(byte[] key);

    void put(byte[] key, byte[] value);

    /** 删除并返回是否实际删除（1 / 0）。 */
    long delete(byte[] key);

    boolean exists(byte[] key);
}
