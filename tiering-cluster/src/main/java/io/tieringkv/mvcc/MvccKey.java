package io.tieringkv.mvcc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 底层存储键编码（ADR-0071）：
 * [len(4)][userKey][writeType(1)][startTS(8)][commitTS(8)]，全部 BE。
 */
public final class MvccKey {

    private MvccKey() {
    }

    public static byte[] encode(byte[] userKey, long startTS,
                                long commitTS, WriteType writeType) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + userKey.length + 1 + 8 + 8)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(userKey.length);
        buffer.put(userKey);
        buffer.put((byte) writeType.ordinal());
        buffer.putLong(startTS);
        buffer.putLong(commitTS);
        return buffer.array();
    }

    public static byte[] userKey(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();
        byte[] key = new byte[length];
        buffer.get(key);
        return key;
    }

    public static long commitTS(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        buffer.position(encoded.length - 8);
        return buffer.getLong();
    }

    public static long startTS(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();
        buffer.position(4 + length + 1);
        return buffer.getLong();
    }

    public static WriteType writeType(byte[] encoded) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int length = buffer.getInt();
        return WriteType.values()[buffer.get(4 + length)];
    }

    /** 判断 encoded 是否属于 userKey（前缀匹配）。 */
    public static boolean startsWith(byte[] encoded, byte[] userKey) {
        ByteBuffer buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        if (buffer.remaining() < 4) {
            return false;
        }
        int length = buffer.getInt();
        if (length != userKey.length || encoded.length < 4 + length + 1 + 16) {
            return false;
        }
        return Arrays.equals(encoded, 4, 4 + length, userKey, 0, userKey.length);
    }
}
