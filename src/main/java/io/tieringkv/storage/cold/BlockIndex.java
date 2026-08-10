package io.tieringkv.storage.cold;

import io.tieringkv.storage.wal.ChecksumValidator;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * SSTable 块索引（ADR-0018）：firstKey / offset / size，块内二分定位。
 */
public final class BlockIndex {

    public static final int MAGIC = 0x544B4958; // "TKIX"
    public static final byte VERSION = 1;
    public static final int HEADER_SIZE = 17;

    private final List<IndexEntry> entries;

    public BlockIndex(List<IndexEntry> entries) {
        this.entries = List.copyOf(entries);
    }

    public record IndexEntry(byte[] firstKey, long offset, int size) {
    }

    public static byte[] encode(List<IndexEntry> entries) {
        int size = HEADER_SIZE;
        for (IndexEntry entry : entries) {
            size += 4 + entry.firstKey().length + 8 + 4;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.putInt(entries.size());
        buffer.putLong(0);
        for (IndexEntry entry : entries) {
            buffer.putInt(entry.firstKey().length);
            buffer.put(entry.firstKey());
            buffer.putLong(entry.offset());
            buffer.putInt(entry.size());
        }
        long crc = ChecksumValidator.crc32c(buffer.array(), HEADER_SIZE, size - HEADER_SIZE);
        buffer.putLong(9, crc);
        return buffer.array();
    }

    public static BlockIndex decode(byte[] data) {
        if (data.length < HEADER_SIZE) {
            throw new ColdCorruptionException("index too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC || buffer.get() != VERSION) {
            throw new ColdCorruptionException("bad index header");
        }
        int count = buffer.getInt();
        long expected = buffer.getLong();
        if (!ChecksumValidator.matches(data, HEADER_SIZE, data.length - HEADER_SIZE, expected)) {
            throw new ColdCorruptionException("index checksum mismatch");
        }
        List<IndexEntry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int keyLength = buffer.getInt();
            byte[] key = new byte[keyLength];
            buffer.get(key);
            long offset = buffer.getLong();
            int blockSize = buffer.getInt();
            entries.add(new IndexEntry(key, offset, blockSize));
        }
        return new BlockIndex(entries);
    }

    /** 定位最接近且 firstKey <= key 的块；key 小于首块时返回首块。 */
    public IndexEntry findBlock(byte[] key) {
        int low = 0;
        int high = entries.size() - 1;
        int answer = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (Keys.compare(entries.get(mid).firstKey(), key) <= 0) {
                answer = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return entries.get(answer);
    }

    public List<IndexEntry> entries() {
        return entries;
    }
}
