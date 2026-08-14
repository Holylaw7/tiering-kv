package io.tieringkv.vector.io;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CacheKey;
import io.tieringkv.storage.io.MappedFile;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 向量索引文件 mmap 读取（ADR-0319）：复用 MappedFile + BlockCache。
 *
 * <p>随机读路径：按记录偏移定位，热记录经 BlockCache
 * （CacheKey = 文件版本 tableId + 记录偏移 blockOffset）缓存原始字节；
 * 全量 CRC 校验由 {@link VectorIndexFile#read} 负责。
 */
public final class VectorIndexMmapReader implements AutoCloseable {

    private final MappedFile mappedFile;
    private final BlockCache cache;
    private final long fileVersion;
    private final int maxLevel;
    private final int dim;
    private final long entryCount;

    public VectorIndexMmapReader(Path file, long fileVersion,
                                 BlockCache cache) throws IOException {
        this.mappedFile = MappedFile.open(file);
        this.cache = cache;
        this.fileVersion = fileVersion;
        ByteBuffer header = mappedFile.buffer().duplicate();
        byte[] magic = new byte[4];
        header.get(magic);
        if (magic[0] != VectorIndexFile.MAGIC[0]
                || magic[1] != VectorIndexFile.MAGIC[1]
                || magic[2] != VectorIndexFile.MAGIC[2]
                || magic[3] != VectorIndexFile.MAGIC[3]) {
            throw new IOException("invalid vector index magic (mmap)");
        }
        int version = header.get() & 0xFF;
        if (version != VectorIndexFile.VERSION) {
            throw new IOException(
                    "unsupported vector index version " + version
                            + " (mmap)");
        }
        this.maxLevel = header.getInt();
        this.dim = header.getInt();
        this.entryCount = header.getLong();
        if (maxLevel < 1 || dim < 0 || entryCount < 0) {
            throw new IOException("invalid vector index header (mmap)");
        }
    }

    public int maxLevel() {
        return maxLevel;
    }

    public int dim() {
        return dim;
    }

    public long entryCount() {
        return entryCount;
    }

    /** 读取全部向量（mmap 顺序遍历 + 记录级 BlockCache）。 */
    public List<Embedding> readAll() throws IOException {
        List<Embedding> result =
                new ArrayList<>((int) Math.min(entryCount, 1_000_000));
        ByteBuffer cursor = mappedFile.buffer().duplicate();
        cursor.position(VectorIndexFile.HEADER_SIZE);
        for (long i = 0; i < entryCount; i++) {
            long offset = cursor.position();
            int idLen = cursor.getShort() & 0xFFFF;
            int recordLen = 2 + idLen + 4 + dim * 4;
            if (offset + recordLen > mappedFile.size() - 4) {
                throw new IOException("truncated vector record at "
                        + offset);
            }
            ByteBuffer record = cache == null ? null
                    : cache.get(new CacheKey(fileVersion, offset));
            if (record == null) {
                record = mappedFile.region(offset, recordLen).view()
                        .duplicate();
                if (cache != null) {
                    cache.put(new CacheKey(fileVersion, offset), record);
                }
            }
            result.add(decode(record.duplicate()));
            cursor.position((int) offset + recordLen);
        }
        return result;
    }

    private static Embedding decode(ByteBuffer record)
            throws IOException {
        int idLen = record.getShort() & 0xFFFF;
        byte[] idBytes = new byte[idLen];
        record.get(idBytes);
        String id = new String(idBytes, StandardCharsets.UTF_8);
        int entryDim = record.getInt();
        float[] values = new float[entryDim];
        for (int d = 0; d < entryDim; d++) {
            values[d] = record.getFloat();
        }
        return new Embedding(id, values);
    }

    @Override
    public void close() {
        mappedFile.close();
    }
}
