package io.tieringkv.mvcc;

import java.util.Arrays;

/** byte[] 键包装（并发 Map 友好）。 */
public record ByteKey(byte[] key) {

    public ByteKey {
        key = key.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ByteKey that && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }
}
