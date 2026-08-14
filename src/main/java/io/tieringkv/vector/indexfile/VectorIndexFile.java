package io.tieringkv.vector.indexfile;

import io.tieringkv.vector.Embedding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32C;

/**
 * 向量索引文件格式（ADR-0319）：magic + version + 唯一向量集 + CRC32C。
 *
 * <p>布局：MAGIC("TVIF",4) + VERSION(u8) + MAX_LEVEL(u32) + DIM(u32) +
 * ENTRY_COUNT(u64) + 记录{idLen u16 + id + dim u32 + float[dim]} + CRC32C(u32)。
 * 加载时按 id hash 重建 HNSW 分层（level = hash(id) % max_level，确定性）。
 */
public final class VectorIndexFile {

    public static final byte[] MAGIC = {'T', 'V', 'I', 'F'};
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 4 + 1 + 4 + 4 + 8;

    /** 索引快照：maxLevel + 统一维度 + 唯一向量集。 */
    public record IndexData(int maxLevel, int dim,
                            List<Embedding> embeddings) {
        public IndexData {
            embeddings = List.copyOf(embeddings);
        }
    }

    private VectorIndexFile() {
    }

    public static byte[] encode(IndexData data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(MAGIC);
        out.writeByte(VERSION);
        out.writeInt(data.maxLevel());
        out.writeInt(data.dim());
        out.writeLong(data.embeddings().size());
        for (Embedding embedding : data.embeddings()) {
            byte[] id = embedding.id()
                    .getBytes(StandardCharsets.UTF_8);
            if (id.length > 0xFFFF) {
                throw new IOException("embedding id too long: "
                        + embedding.id().length());
            }
            out.writeShort(id.length);
            out.write(id);
            out.writeInt(embedding.values().length);
            for (float value : embedding.values()) {
                out.writeFloat(value);
            }
        }
        out.flush();
        byte[] payload = bytes.toByteArray();
        CRC32C crc = new CRC32C();
        crc.update(payload);
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream tail = new DataOutputStream(all);
        tail.write(payload);
        tail.writeInt((int) crc.getValue());
        tail.flush();
        return all.toByteArray();
    }

    public static IndexData decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < HEADER_SIZE + 4) {
            throw new IOException("vector index file too short");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes));
        byte[] magic = new byte[4];
        in.readFully(magic);
        if (magic[0] != MAGIC[0] || magic[1] != MAGIC[1]
                || magic[2] != MAGIC[2] || magic[3] != MAGIC[3]) {
            throw new IOException("invalid vector index magic");
        }
        int version = in.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException(
                    "unsupported vector index version " + version);
        }
        int maxLevel = in.readInt();
        int dim = in.readInt();
        long count = in.readLong();
        if (maxLevel < 1 || dim < 0 || count < 0
                || count > 100_000_000) {
            throw new IOException("invalid vector index header "
                    + "(maxLevel=" + maxLevel + ", dim=" + dim
                    + ", count=" + count + ")");
        }
        List<Embedding> embeddings =
                new ArrayList<>((int) Math.min(count, 1_000_000));
        for (long i = 0; i < count; i++) {
            int idLen = in.readUnsignedShort();
            byte[] idBytes = new byte[idLen];
            in.readFully(idBytes);
            String id = new String(idBytes, StandardCharsets.UTF_8);
            int entryDim = in.readInt();
            if (entryDim != dim) {
                throw new IOException("dimension mismatch: expected "
                        + dim + " got " + entryDim);
            }
            float[] values = new float[entryDim];
            for (int d = 0; d < entryDim; d++) {
                values[d] = in.readFloat();
            }
            embeddings.add(new Embedding(id, values));
        }
        int expectedCrc = in.readInt();
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("vector index CRC mismatch");
        }
        return new IndexData(maxLevel, dim, embeddings);
    }

    /** 原子写：临时文件 + fsync + rename（防半写文件被加载）。 */
    public static void write(Path file, IndexData data)
            throws IOException {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = absolute.resolveSibling(absolute.getFileName()
                + ".tmp");
        byte[] encoded = encode(data);
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    public static IndexData read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }
}
