package io.tieringkv.storage.wal;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * WAL 段读取器（ADR-0016 + v6.5.0 预分配/O_DIRECT 零尾识别）：
 * 按记录边界顺序读取；物理 EOF、尾部截断或预分配全 0 未写区域均返回 null；
 * 损坏记录（非法 magic、负长度或 CRC 失配）抛 {@link WalCorruptionException}。
 */
public final class WALReader implements AutoCloseable {

    private final InputStream in;
    private final Path path;
    private long offset;
    private boolean zeroTailReached;

    public WALReader(Path segmentPath) throws IOException {
        this(segmentPath, 0);
    }

    public WALReader(Path segmentPath, long startOffset) throws IOException {
        this.path = segmentPath;
        this.in = new BufferedInputStream(Files.newInputStream(segmentPath), 64 * 1024);
        in.skipNBytes(startOffset); // 不足时抛 EOFException
        this.offset = startOffset;
    }

    /** 返回下一条记录；正常 EOF、预分配全 0 尾区或尾部截断均返回 null。 */
    public WALEntry next() throws IOException {
        byte[] header = readFully(WALRecord.HEADER_SIZE);
        if (header == null) {
            return null;
        }
        int magic = readInt(header, 0);
        if (magic == 0) {
            if (isAllZero(header)) {
                zeroTailReached = true;
                return null; // 预分配 / O_DIRECT 4KB 对齐尾页的干净逻辑 EOF
            }
            throw new WalCorruptionException("corrupt zero-magic header");
        }
        if (magic != WALRecord.MAGIC) {
            throw new WalCorruptionException("bad magic: 0x" + Integer.toHexString(magic));
        }
        int keyLength = readInt(header, 14);
        int valueLength = readInt(header, 18);
        if (keyLength < 0 || valueLength < 0) {
            throw new WalCorruptionException("negative length in header");
        }
        long payloadLong = (long) keyLength + (long) valueLength;
        if (payloadLong > 256L * 1024 * 1024) {
            throw new WalCorruptionException("unreasonable payload length: " + payloadLong);
        }
        int payloadLength = (int) payloadLong;
        byte[] rest = readFully(payloadLength + WALRecord.CHECKSUM_SIZE);
        if (rest == null) {
            return null; // 尾部截断：丢弃
        }
        byte[] record = Arrays.copyOf(header, header.length + rest.length);
        System.arraycopy(rest, 0, record, header.length, rest.length);
        WALEntry decoded = WALRecord.decode(record);
        offset += record.length;
        return decoded;
    }

    /** 是否因为命中预分配/O_DIRECT 零填充区而结束（恢复时保留预分配物理块不截断）。 */
    public boolean reachedZeroTail() {
        return zeroTailReached;
    }

    /** 已消费有效字节数（含 startOffset），供截断或续写定位使用。 */
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

    private static boolean isAllZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
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
