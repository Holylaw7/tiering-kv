package io.tieringkv.mvcc.index;

import io.tieringkv.mvcc.MvccEntry;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;

/**
 * MVCC 索引写入（ADR-0080）：
 * MAGIC + VERSION + COUNT + (KEY_LEN + USER_KEY + START_TS + COMMIT_TS + TYPE) + CRC32。
 */
public final class MvccIndexWriter {

    public static final byte[] MAGIC =
            {'T', 'K', 'M', 'V', 'I', 'D', 'X'};
    public static final int VERSION = 1;

    private MvccIndexWriter() {
    }

    public static void write(Path path, MvccIndexSnapshot snapshot)
            throws IOException {
        try (OutputStream raw = Files.newOutputStream(path);
             OutputStream out = new BufferedOutputStream(raw)) {
            CRC32 crc = new CRC32();
            out.write(MAGIC);
            crc.update(MAGIC);
            writeInt(out, crc, VERSION);
            writeLong(out, crc, snapshot.versions().size());
            for (MvccEntry version : snapshot.versions()) {
                byte[] key = version.keyBytes();
                writeInt(out, crc, key.length);
                out.write(key);
                crc.update(key);
                byte[] value = version.valueBytes();
                writeInt(out, crc, value == null ? -1 : value.length);
                if (value != null) {
                    out.write(value);
                    crc.update(value);
                }
                writeLong(out, crc, version.startTS());
                writeLong(out, crc, version.commitTS());
                out.write(version.writeType().ordinal());
                crc.update(version.writeType().ordinal());
            }
            writeInt(out, crc, (int) crc.getValue());
        }
    }

    private static void writeInt(OutputStream out, CRC32 crc, int value)
            throws IOException {
        byte[] bytes = new byte[]{
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
        out.write(bytes);
        crc.update(bytes);
    }

    private static void writeLong(OutputStream out, CRC32 crc, long value)
            throws IOException {
        byte[] bytes = new byte[]{
                (byte) (value >>> 56), (byte) (value >>> 48),
                (byte) (value >>> 40), (byte) (value >>> 32),
                (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value};
        out.write(bytes);
        crc.update(bytes);
    }
}
