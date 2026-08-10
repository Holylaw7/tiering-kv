package io.tieringkv.storage.wal;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * WAL 段读取器（ADR-0016）：按记录边界顺序读取；EOF 返回 null；
 * 损坏记录抛 {@link WalCorruptionException}。
 */
public final class WALReader implements AutoCloseable {

    private final InputStream in;
    private final Path path;
    private long offset;

    public WALReader(Path segmentPath) throws IOException {
        this(segmentPath, 0);
    }

    public WALReader(Path segmentPath, long startOffset) throws IOException {
        this.path = segmentPath;
        this.in = new BufferedInputStream(Files.newInputStream(segmentPath));
        in.skipNBytes(startOffset); // 不足时抛 EOFException
        this.offset = startOffset;
    }

    /** 返回下一条记录；正常 EOF 返回 null，尾部截断也返回 null（视为崩溃残尾）。 */
    public WALEntry next() throws IOException {
        byte[] header = readFully(WALRecord.HEADER_SIZE);
        if (header == null) {
            return null;
        }
        int keyLength = readInt(header, 14);
        int valueLength = readInt(header, 18);
        if (keyLength < 0 || valueLength < 0) {
            throw new WalCorruptionException("negative length in header");
        }
        int payloadLength = keyLength + valueLength;
        byte[] rest = readFully(payloadLength + WALRecord.CHECKSUM_SIZE);
        if (rest == null) {
            return null; // 尾部截断：丢弃
        }
        byte[] record = Arrays.copyOf(header, header.length + rest.length);
        System.arraycopy(rest, 0, record, header.length, rest.length);
        offset += record.length;
        return WALRecord.decode(record);
    }

    /** 已消费字节数（含 startOffset），供截断使用。 */
    public long offset() {
        return offset;
    }

    public Path path() {
        return path;
    }

    private byte[] readFully(int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n == -1) {
                return null; // 头部或 payload 截断均视为残尾
            }
            read += n;
        }
        return buffer;
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
