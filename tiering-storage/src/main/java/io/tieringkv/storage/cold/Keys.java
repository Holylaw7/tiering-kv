package io.tieringkv.storage.cold;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** 二进制键比较工具（无符号字典序，与 MemTable 一致）。 */
public final class Keys {

    private Keys() {
    }

    public static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    public static ByteBuffer wrap(byte[] key) {
        return ByteBuffer.wrap(key);
    }
}
