package io.tieringkv.mvcc.index;

import io.tieringkv.mvcc.MvccEntry;
import io.tieringkv.mvcc.WriteType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/** MVCC 索引读取（ADR-0080）：MAGIC/VERSION/CRC 校验后加载版本链。 */
public final class MvccIndexReader {

    private static final int HEADER_BYTES =
            MvccIndexWriter.MAGIC.length + 4 + 8;
    private static final int FOOTER_BYTES = 4;

    private MvccIndexReader() {
    }

    public static MvccIndexSnapshot read(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        if (data.length < HEADER_BYTES + FOOTER_BYTES) {
            throw new IOException("mvcc index truncated: " + path);
        }
        ByteBuffer buffer = ByteBuffer.wrap(data)
                .order(ByteOrder.BIG_ENDIAN);
        byte[] magic = new byte[MvccIndexWriter.MAGIC.length];
        buffer.get(magic);
        if (!java.util.Arrays.equals(magic, MvccIndexWriter.MAGIC)) {
            throw new IOException("invalid mvcc index magic: " + path);
        }
        int version = buffer.getInt();
        if (version != MvccIndexWriter.VERSION) {
            throw new IOException("unsupported mvcc index version: " + version);
        }
        long count = buffer.getLong();
        if (count < 0 || count > data.length) {
            throw new IOException("invalid mvcc index count: " + count);
        }
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length - FOOTER_BYTES);
        int expected = buffer.getInt(data.length - FOOTER_BYTES);
        if ((int) crc.getValue() != expected) {
            throw new IOException("mvcc index crc mismatch: " + path);
        }
        List<MvccEntry> versions = new ArrayList<>((int) Math.min(
                count, Integer.MAX_VALUE));
        for (long i = 0; i < count; i++) {
            if (buffer.remaining() < 4) {
                throw new IOException("mvcc index record truncated");
            }
            int keyLength = buffer.getInt();
            if (keyLength < 0 || keyLength > buffer.remaining()) {
                throw new IOException("invalid mvcc index key length");
            }
            byte[] key = new byte[keyLength];
            buffer.get(key);
            if (buffer.remaining() < 4 + 8 + 8 + 1) {
                throw new IOException("mvcc index record truncated");
            }
            int valueLength = buffer.getInt();
            byte[] value = null;
            if (valueLength == -1) {
                value = null;
            } else if (valueLength >= 0 && valueLength <= buffer.remaining()) {
                value = new byte[valueLength];
                buffer.get(value);
            } else {
                throw new IOException("invalid mvcc index value length");
            }
            long startTS = buffer.getLong();
            long commitTS = buffer.getLong();
            int type = buffer.get();
            if (type < 0 || type >= WriteType.values().length) {
                throw new IOException("invalid mvcc write type: " + type);
            }
            versions.add(new MvccEntry(key, value, startTS, commitTS,
                    WriteType.values()[type]));
        }
        return MvccIndexSnapshot.of(versions);
    }
}
