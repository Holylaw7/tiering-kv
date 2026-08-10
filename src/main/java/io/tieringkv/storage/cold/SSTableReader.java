package io.tieringkv.storage.cold;

import io.tieringkv.storage.cold.filter.BloomFilter;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * SSTable 读取器（ADR-0018）：GET = Bloom → Index 二分 → Block 解码 → 块内二分；
 * 同时支持顺序迭代（DiskIterator）。
 */
public final class SSTableReader implements AutoCloseable {

    private final Path path;
    private final SSTableMeta meta;
    private final FileChannel channel;
    private final BlockIndex index;
    private final BloomFilter bloom;

    private SSTableReader(Path path, SSTableMeta meta, FileChannel channel,
                          BlockIndex index, BloomFilter bloom) {
        this.path = path;
        this.meta = meta;
        this.channel = channel;
        this.index = index;
        this.bloom = bloom;
    }

    public static SSTableReader open(SSTableMeta meta, Path directory) throws IOException {
        Path path = meta.path(directory);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = channel.size();
            byte[] footer = readFully(channel, fileSize - SSTableWriter.FOOTER_SIZE,
                    SSTableWriter.FOOTER_SIZE);
            SSTableWriter.FooterInfo footerInfo = SSTableWriter.decodeFooter(footer);
            byte[] indexBytes = readFully(channel, footerInfo.indexOffset(), (int) footerInfo.indexSize());
            byte[] bloomBytes = readFully(channel, footerInfo.bloomOffset(), (int) footerInfo.bloomSize());
            return new SSTableReader(path, meta, channel,
                    BlockIndex.decode(indexBytes), BloomFilter.deserialize(bloomBytes));
        } catch (Exception e) {
            channel.close();
            throw e;
        }
    }

    public KeyValueEntry get(byte[] key) throws IOException {
        if (!bloom.mightContain(key)) {
            return null;
        }
        BlockIndex.IndexEntry blockEntry = index.findBlock(key);
        byte[] blockBytes = readFully(channel, blockEntry.offset(), blockEntry.size());
        List<KeyValueEntry> entries = Block.decode(blockBytes);
        int position = binarySearch(entries, key);
        return position >= 0 ? entries.get(position) : null;
    }

    public boolean mightContain(byte[] key) {
        return bloom.mightContain(key);
    }

    public DiskIterator iterator() throws IOException {
        return new DiskIterator(this, index.entries());
    }

    /** 供 DiskIterator 读取指定数据块。 */
    byte[] readBlock(BlockIndex.IndexEntry blockEntry) throws IOException {
        return readFully(channel, blockEntry.offset(), blockEntry.size());
    }

    public SSTableMeta meta() {
        return meta;
    }

    public Path path() {
        return path;
    }

    private static int binarySearch(List<KeyValueEntry> entries, byte[] key) {
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int cmp = Keys.compare(entries.get(mid).key(), key);
            if (cmp < 0) {
                low = mid + 1;
            } else if (cmp > 0) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return -1;
    }

    static byte[] readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        int read = 0;
        while (read < length) {
            int n = channel.read(buffer, position + read);
            if (n < 0) {
                throw new ColdCorruptionException("unexpected eof in " + channel);
            }
            read += n;
        }
        return buffer.array();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
