package io.tieringkv.storage.wal;

import java.util.zip.CRC32C;

/** CRC32C 校验工具（ADR-0015）：覆盖记录头部至 payload 末尾。 */
public final class ChecksumValidator {

    private ChecksumValidator() {
    }

    public static long crc32c(byte[] data, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, 0, length);
        return crc.getValue();
    }

    public static long crc32c(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
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
}
