package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.wal.ChecksumValidator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * SSTable 数据块（ADR-0018 / B3 块内零全量解码查找优化）：
 * [MAGIC 4B][VERSION 1B][ENTRY_COUNT 4B][CRC32C 8B] + 条目。
 */
public final class Block {

    public static final int MAGIC = 0x544B4442; // "TKDB"
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 17;
    private static final int ENTRY_FIXED_META_BYTES = 32; // create + update + expire + version

    private Block() {
    }

    public static byte[] encode(List<KeyValueEntry> entries) {
        int size = HEADER_SIZE;
        for (KeyValueEntry entry : entries) {
            size += 1 + 4 + 4 + ENTRY_FIXED_META_BYTES + entry.key().length
                    + (entry.value() == null ? 0 : entry.value().length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(entries.size());
        buffer.putLong(0); // CRC 占位
        for (KeyValueEntry entry : entries) {
            buffer.put(entry.deleted() ? (byte) 2 : (byte) 1);
            buffer.putInt(entry.key().length);
            byte[] value = entry.value() == null ? new byte[0] : entry.value();
            buffer.putInt(value.length);
            buffer.putLong(entry.createTimestamp());
            buffer.putLong(entry.updateTimestamp());
            buffer.putLong(entry.expireTimestamp());
            buffer.putLong(entry.version());
            buffer.put(entry.key());
            buffer.put(value);
        }
        long crc = ChecksumValidator.crc32c(buffer.array(), HEADER_SIZE, size - HEADER_SIZE);
        buffer.putLong(9, crc);
        return buffer.array();
    }

    public static List<KeyValueEntry> decode(byte[] data) {
        return decode(ByteBuffer.wrap(data));
    }

    /** 从 ByteBuffer 解码全部条目（顺序迭代 / Compaction 用）。 */
    public static List<KeyValueEntry> decode(ByteBuffer data) {
        ByteBuffer buffer = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        int count = validateHeader(buffer, true);
        List<KeyValueEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte type = buffer.get();
            int keyLength = buffer.getInt();
            int valueLength = buffer.getInt();
            if (keyLength < 0 || valueLength < 0) {
                throw new ColdCorruptionException("negative length");
            }
            long create = buffer.getLong();
            long update = buffer.getLong();
            long expire = buffer.getLong();
            long version = buffer.getLong();
            byte[] key = new byte[keyLength];
            buffer.get(key);
            byte[] value = valueLength == 0 ? null : new byte[valueLength];
            if (value != null) {
                buffer.get(value);
            }
            boolean deleted = type == 2;
            if (type != 1 && type != 2) {
                throw new ColdCorruptionException("bad entry type");
            }
            entries.add(new KeyValueEntry(key, value, create, update, expire, version, deleted,
                    KeyValueEntry.sizeOf(key, value)));
        }
        return entries;
    }

    /**
     * 块内按需定位查找（B3 优化）：直接在 {@link ByteBuffer} 上跳过非目标条目，
     * 仅在命中目标键时分配 1 条 {@link KeyValueEntry}（未命中 0 对象分配）。
     *
     * @param verifyChecksum 首次磁盘读取传 {@code true} 校验 CRC32C；命中已校验 BlockCache 传 {@code false} 省去重复校验
     */
    public static KeyValueEntry findEntry(ByteBuffer data, byte[] targetKey, boolean verifyChecksum) {
        ByteBuffer buffer = data.duplicate().order(ByteOrder.BIG_ENDIAN);
        int count = validateHeader(buffer, verifyChecksum);
        int targetLen = targetKey.length;
        for (int i = 0; i < count; i++) {
            byte type = buffer.get();
            if (type != 1 && type != 2) {
                throw new ColdCorruptionException("bad entry type");
            }
            int keyLength = buffer.getInt();
            int valueLength = buffer.getInt();
            if (keyLength < 0 || valueLength < 0) {
                throw new ColdCorruptionException("negative length");
            }
            int metaPos = buffer.position();
            int keyPos = metaPos + ENTRY_FIXED_META_BYTES;
            if (keyLength == targetLen && regionEquals(buffer, keyPos, targetKey)) {
                long create = buffer.getLong();
                long update = buffer.getLong();
                long expire = buffer.getLong();
                long version = buffer.getLong();
                byte[] key = new byte[keyLength];
                buffer.get(key);
                byte[] value = valueLength == 0 ? null : new byte[valueLength];
                if (value != null) {
                    buffer.get(value);
                }
                return new KeyValueEntry(key, value, create, update, expire, version, type == 2,
                        KeyValueEntry.sizeOf(key, value));
            }
            buffer.position(keyPos + keyLength + valueLength);
        }
        return null;
    }

    public static KeyValueEntry findEntry(ByteBuffer data, byte[] targetKey) {
        return findEntry(data, targetKey, true);
    }

    private static int validateHeader(ByteBuffer buffer, boolean verifyChecksum) {
        if (buffer.remaining() < HEADER_SIZE) {
            throw new ColdCorruptionException("block too short");
        }
        if (buffer.getInt() != MAGIC) {
            throw new ColdCorruptionException("bad block magic");
        }
        if (buffer.get() != VERSION) {
            throw new ColdCorruptionException("bad block version");
        }
        int count = buffer.getInt();
        long expected = buffer.getLong();
        if (verifyChecksum && ChecksumValidator.crc32c(buffer.duplicate()) != expected) {
            throw new ColdCorruptionException("block checksum mismatch");
        }
        return count;
    }

    private static boolean regionEquals(ByteBuffer buffer, int offset, byte[] target) {
        for (int i = 0; i < target.length; i++) {
            if (buffer.get(offset + i) != target[i]) {
                return false;
            }
        }
        return true;
    }
}
