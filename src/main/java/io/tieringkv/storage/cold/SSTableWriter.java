package io.tieringkv.storage.cold;

import io.tieringkv.storage.cold.filter.BloomFilter;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.wal.ChecksumValidator;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SSTable 写入器（ADR-0018）：流式写 Data Blocks → Index Block → Bloom →
 * Footer；要求输入按键升序且键唯一。
 */
public final class SSTableWriter implements AutoCloseable {

    public static final int FOOTER_SIZE = 45;
    public static final int FOOTER_MAGIC = 0x544B5346; // "TKSF"

    private final long id;
    private final Path path;
    private final OutputStream out;
    private final int blockTargetBytes;
    private final List<BlockIndex.IndexEntry> index = new ArrayList<>();
    private final List<KeyValueEntry> blockBuffer = new ArrayList<>();
    private final BloomFilter bloom;
    private int blockBytes;
    private long offset;
    private long entryCount;
    private byte[] firstKey;
    private byte[] lastKey;
    private boolean finished;

    public SSTableWriter(
            Path directory,
            long id,
            int expectedInsertions,
            double bitsPerKey,
            int blockTargetBytes) throws IOException {
        this.id = id;
        this.path = directory.resolve(String.format("%08d.sst", id));
        this.out = new BufferedOutputStream(new FileOutputStream(path.toFile()), 64 * 1024);
        this.blockTargetBytes = blockTargetBytes;
        this.bloom = new BloomFilter(expectedInsertions, bitsPerKey);
    }

    public void writeEntry(KeyValueEntry entry) throws IOException {
        if (firstKey == null) {
            firstKey = entry.key();
        }
        lastKey = entry.key();
        blockBuffer.add(entry);
        blockBytes += estimateSize(entry);
        bloom.put(entry.key());
        entryCount++;
        if (blockBytes >= blockTargetBytes) {
            flushBlock();
        }
    }

    public SSTableMeta finish() throws IOException {
        flushBlock();
        byte[] indexBytes = BlockIndex.encode(index);
        out.write(indexBytes);
        long indexOffset = offset;
        offset += indexBytes.length;

        byte[] bloomBytes = bloom.serialize();
        out.write(bloomBytes);
        long bloomOffset = offset;
        offset += bloomBytes.length;

        out.write(encodeFooter(indexOffset, indexBytes.length, bloomOffset, bloomBytes.length));
        offset += FOOTER_SIZE;
        out.flush();
        out.close();
        finished = true;
        return new SSTableMeta(id, path.getFileName().toString(), entryCount, offset, firstKey, lastKey);
    }

    @Override
    public void close() throws IOException {
        if (!finished) {
            try {
                out.close();
            } finally {
                Files.deleteIfExists(path); // 未完成文件不落库
            }
        }
    }

    private void flushBlock() throws IOException {
        if (blockBuffer.isEmpty()) {
            return;
        }
        byte[] data = Block.encode(blockBuffer);
        out.write(data);
        index.add(new BlockIndex.IndexEntry(blockBuffer.get(0).key(), offset, data.length));
        offset += data.length;
        blockBuffer.clear();
        blockBytes = 0;
    }

    private static int estimateSize(KeyValueEntry entry) {
        return 1 + 4 + 4 + 8 + 8 + 8 + 8 + entry.key().length
                + (entry.value() == null ? 0 : entry.value().length);
    }

    static byte[] encodeFooter(long indexOffset, int indexSize, long bloomOffset, int bloomSize) {
        ByteBuffer buffer = ByteBuffer.allocate(FOOTER_SIZE).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(FOOTER_MAGIC);
        buffer.put((byte) 1);
        buffer.putLong(indexOffset);
        buffer.putLong(indexSize);
        buffer.putLong(bloomOffset);
        buffer.putLong(bloomSize);
        long crc = ChecksumValidator.crc32c(buffer.array(), 0, buffer.position());
        buffer.putLong(crc);
        return buffer.array();
    }

    static FooterInfo decodeFooter(byte[] footer) {
        if (footer.length != FOOTER_SIZE) {
            throw new ColdCorruptionException("bad footer size");
        }
        ByteBuffer buffer = ByteBuffer.wrap(footer).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != FOOTER_MAGIC) {
            throw new ColdCorruptionException("bad footer magic");
        }
        if (buffer.get() != 1) {
            throw new ColdCorruptionException("bad footer version");
        }
        long indexOffset = buffer.getLong();
        long indexSize = buffer.getLong();
        long bloomOffset = buffer.getLong();
        long bloomSize = buffer.getLong();
        long expected = buffer.getLong();
        if (!ChecksumValidator.matches(footer, 0, FOOTER_SIZE - 8, expected)) {
            throw new ColdCorruptionException("footer checksum mismatch");
        }
        return new FooterInfo(indexOffset, indexSize, bloomOffset, bloomSize);
    }

    record FooterInfo(long indexOffset, long indexSize, long bloomOffset, long bloomSize) {
    }
}
