package io.tieringkv.cluster.sharding;

import java.util.Arrays;

/** 分区键（ADR-0035）：按 key 计算 hash slot。 */
public final class PartitionKey {

    private final byte[] key;
    private final int slot;

    public PartitionKey(byte[] key) {
        this.key = key.clone();
        this.slot = HashSlotRouter.slot(key);
    }

    public byte[] key() {
        return key;
    }

    public int slot() {
        return slot;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PartitionKey that && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }
}
