package io.tieringkv.storage.io;

import io.tieringkv.storage.cold.BlockIndex;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.cold.SSTableReader;
import io.tieringkv.storage.cold.SSTableWriter;
import io.tieringkv.storage.cold.filter.BloomFilter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * mmap SSTable 读取器（ADR-0026）：MappedByteBuffer 零拷贝块读，
 * Footer/Index/Bloom 在 open 时校验加载。
 */
public final class MmapSSTableReader extends SSTableReader {

    private final MappedFile mappedFile;

    private MmapSSTableReader(
            MappedFile mappedFile, SSTableMeta meta, BlockIndex index, BloomFilter bloom) {
        super(meta.path(mappedFile.path()), meta, null, index, bloom);
        this.mappedFile = mappedFile;
    }

    public static MmapSSTableReader open(SSTableMeta meta, Path directory) throws IOException {
        Path path = meta.path(directory);
        MappedFile mappedFile = MappedFile.open(path);
        try {
            ByteBuffer file = mappedFile.buffer();
            byte[] footer = new byte[SSTableWriter.FOOTER_SIZE];
            ByteBuffer footerRegion = file.duplicate();
            footerRegion.position(file.limit() - SSTableWriter.FOOTER_SIZE);
            footerRegion.get(footer);
            SSTableWriter.FooterInfo info = SSTableWriter.decodeFooter(footer);
            byte[] indexBytes = readRegion(file, info.indexOffset(), (int) info.indexSize());
            byte[] bloomBytes = readRegion(file, info.bloomOffset(), (int) info.bloomSize());
            return new MmapSSTableReader(
                    mappedFile, meta,
                    BlockIndex.decode(indexBytes),
                    BloomFilter.deserialize(bloomBytes));
        } catch (Exception e) {
            mappedFile.close();
            throw e;
        }
    }

    @Override
    protected ByteBuffer readBlockBuffer(BlockIndex.IndexEntry blockEntry) {
        return mappedFile.region(blockEntry.offset(), blockEntry.size()).view();
    }

    @Override
    public void close() {
        mappedFile.close();
    }

    private static byte[] readRegion(ByteBuffer file, long offset, int length) {
        ByteBuffer region = file.duplicate();
        region.position((int) offset);
        byte[] bytes = new byte[length];
        region.get(bytes);
        return bytes;
    }
}
