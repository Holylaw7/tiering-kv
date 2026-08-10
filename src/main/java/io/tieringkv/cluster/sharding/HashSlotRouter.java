package io.tieringkv.cluster.sharding;

/**
 * 16384 hash slot 路由（ADR-0035）：CRC16(key) % 16384，
 * 与 Redis Cluster 语义一致。
 */
public final class HashSlotRouter {

    public static final int SLOT_COUNT = 16_384;

    private HashSlotRouter() {
    }

    public static int slot(byte[] key) {
        return crc16(key) & 0x3FFF;
    }

    /** CRC-16/CCITT（XMODEM）：poly 0x1021，init 0，无反射。 */
    public static int crc16(byte[] data) {
        int crc = 0x0000;
        for (byte b : data) {
            crc ^= (b & 0xff) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return crc;
    }
}
