package io.tieringkv.storage.cold.filter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Bloom Filter（ADR-0018）：双哈希（FNV-1a 64 + splitmix64），
 * 支持 mightContain 与序列化；bits-per-key=10 时 FPR < 1%。
 */
public final class BloomFilter {

    private static final double LN2 = Math.log(2);

    private final long[] bits;
    private final int bitCount;
    private final int hashCount;

    public BloomFilter(int expectedInsertions, double bitsPerKey) {
        if (expectedInsertions <= 0 || bitsPerKey <= 0) {
            throw new IllegalArgumentException("expectedInsertions and bitsPerKey must be positive");
        }
        long bitsLong = Math.max(64, (long) Math.ceil(expectedInsertions * bitsPerKey));
        this.bitCount = (int) Math.min(bitsLong, Integer.MAX_VALUE);
        this.hashCount = Math.max(1, (int) Math.round(bitCount / (double) expectedInsertions * LN2));
        this.bits = new long[(bitCount + 63) / 64];
    }

    private BloomFilter(long[] bits, int bitCount, int hashCount) {
        this.bits = bits;
        this.bitCount = bitCount;
        this.hashCount = hashCount;
    }

    public void put(byte[] key) {
        long h1 = fnv1a64(key);
        long h2 = splitmix64(h1);
        for (int i = 0; i < hashCount; i++) {
            int index = (int) (Math.floorMod(h1 + (long) i * h2, bitCount));
            bits[index >>> 6] |= 1L << (index & 63);
        }
    }

    public boolean mightContain(byte[] key) {
        long h1 = fnv1a64(key);
        long h2 = splitmix64(h1);
        for (int i = 0; i < hashCount; i++) {
            int index = (int) (Math.floorMod(h1 + (long) i * h2, bitCount));
            if ((bits[index >>> 6] & (1L << (index & 63))) == 0) {
                return false;
            }
        }
        return true;
    }

    /** 序列化：[BIT_COUNT 4B][HASH_COUNT 4B][BITS]。 */
    public byte[] serialize() {
        ByteBuffer buffer = ByteBuffer.allocate(8 + bits.length * 8).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(bitCount);
        buffer.putInt(hashCount);
        for (long word : bits) {
            buffer.putLong(word);
        }
        return buffer.array();
    }

    public static BloomFilter deserialize(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int bitCount = buffer.getInt();
        int hashCount = buffer.getInt();
        long[] bits = new long[(bitCount + 63) / 64];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = buffer.getLong();
        }
        return new BloomFilter(bits, bitCount, hashCount);
    }

    public int hashCount() {
        return hashCount;
    }

    public int bitCount() {
        return bitCount;
    }

    private static long fnv1a64(byte[] data) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long splitmix64(long value) {
        value += 0x9e3779b97f4a7c15L;
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BloomFilter that
                && bitCount == that.bitCount
                && hashCount == that.hashCount
                && Arrays.equals(bits, that.bits);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * bitCount + hashCount) + Arrays.hashCode(bits);
    }
}
