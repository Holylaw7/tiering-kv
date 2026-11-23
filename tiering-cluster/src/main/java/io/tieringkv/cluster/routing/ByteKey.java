package io.tieringkv.cluster.routing;

import java.util.Arrays;

/** byte[] 键包装（ConcurrentHashMap 友好）。 */
record ByteKey(byte[] key) {

    ByteKey {
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
