package io.tieringkv.storage.wal;

import java.nio.ByteBuffer;
import java.util.zip.CRC32C;

/**
 * CRC32C 校验工具（ADR-0015 / v6.5.0 线程本地复用优化）：
 * 覆盖记录头部至 payload 末尾；复用 ThreadLocal CRC32C 避免热路径高频对象分配。
 */
public final class ChecksumValidator {

    private static final ThreadLocal<CRC32C> TL_CRC = ThreadLocal.withInitial(CRC32C::new);

    private ChecksumValidator() {
    }

    public static long crc32c(byte[] data, int length) {
        return crc32c(data, 0, length);
    }

    public static long crc32c(byte[] data, int offset, int length) {
        CRC32C crc = TL_CRC.get();
        crc.reset();
        crc.update(data, offset, length);
        return crc.getValue();
    }

    /** 校验 data[0,length) 的 CRC 是否等于 expected。 */
    public static boolean matches(byte[] data, int length, long expected) {
        return crc32c(data, length) == expected;
    }

    /** 校验 data[offset, offset+length) 的 CRC 是否等于 expected。 */
    public static boolean matches(byte[] data, int offset, int length, long expected) {
        return crc32c(data, offset, length) == expected;
    }

    /** 计算 ByteBuffer 剩余内容的 CRC32C（不移动调用方 position/limit）。 */
    public static long crc32c(ByteBuffer data) {
        CRC32C crc = TL_CRC.get();
        crc.reset();
        crc.update(data.duplicate());
        return crc.getValue();
    }

    /** 计算 ByteBuffer[offset, offset + length) 区间的 CRC32C（不移动调用方 position/limit）。 */
    public static long crc32c(ByteBuffer data, int offset, int length) {
        CRC32C crc = TL_CRC.get();
        crc.reset();
        crc.update(data.slice(offset, length));
        return crc.getValue();
    }
}
