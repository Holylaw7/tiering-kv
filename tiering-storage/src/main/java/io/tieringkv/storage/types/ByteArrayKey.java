package io.tieringkv.storage.types;

import java.util.Arrays;

/** 二进制安全键包装（hash/set/zset 成员与字段）。 */
public record ByteArrayKey(byte[] data) {

    public ByteArrayKey {
        data = data.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ByteArrayKey that
                && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }
}
