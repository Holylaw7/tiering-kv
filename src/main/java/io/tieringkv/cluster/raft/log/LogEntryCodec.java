package io.tieringkv.cluster.raft.log;

import io.tieringkv.cluster.raft.LogEntry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32C;

/**
 * RaftLog 二进制编解码（ADR-0039）：
 * MAGIC(4B) | VERSION(1B) | TERM(8B) | INDEX(8B) | COMMAND_TYPE(1B)
 * | DATA_LENGTH(4B) | DATA(N) | CRC32C(4B)。
 * 当前 COMMAND_TYPE 保留为 0（OPAQUE），命令语义由 ReplicatedStorageEngine
 * 的 payload 自行承载。
 */
public final class LogEntryCodec {

    public static final int MAGIC = 0x524C4F47; // 'RLOG'
    public static final byte VERSION = 1;

    private static final int HEADER_SIZE = 4 + 1 + 8 + 8 + 1 + 4;
    private static final int CRC_SIZE = 4;
    private static final int PAYLOAD_START = 4; // MAGIC 之后

    private LogEntryCodec() {
    }

    public static byte[] encode(LogEntry entry) {
        byte[] data = entry.command();
        ByteBuffer payload = ByteBuffer.allocate(HEADER_SIZE - 4 + data.length)
                .order(ByteOrder.BIG_ENDIAN);
        payload.put(VERSION);
        payload.putLong(entry.term());
        payload.putLong(entry.index());
        payload.put((byte) 0); // COMMAND_TYPE: OPAQUE
        payload.putInt(data.length);
        payload.put(data);
        byte[] payloadBytes = payload.array();

        ByteBuffer out = ByteBuffer.allocate(4 + payloadBytes.length + CRC_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        out.putInt(MAGIC);
        out.put(payloadBytes);
        out.putInt(crc32c(payloadBytes));
        return out.array();
    }

    /** 解码并校验；损坏抛出 {@link CorruptionException}。 */
    public static DecodedEntry decode(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE + CRC_SIZE) {
            throw new CorruptionException("truncated header");
        }
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new CorruptionException("bad magic 0x" + Integer.toHexString(magic));
        }
        int payloadStart = buffer.position();
        byte version = buffer.get();
        if (version != VERSION) {
            throw new CorruptionException("unsupported version " + version);
        }
        long term = buffer.getLong();
        long index = buffer.getLong();
        byte commandType = buffer.get();
        int dataLength = buffer.getInt();
        if (dataLength < 0 || buffer.remaining() < dataLength + CRC_SIZE) {
            throw new CorruptionException("bad data length " + dataLength);
        }
        byte[] data = new byte[dataLength];
        buffer.get(data);
        int payloadEnd = buffer.position();
        int expectedCrc = buffer.getInt();
        int actualCrc = crc32c(buffer, payloadStart, payloadEnd);
        if (expectedCrc != actualCrc) {
            throw new CorruptionException("crc mismatch");
        }
        if (commandType != 0) {
            throw new CorruptionException("unsupported command type " + commandType);
        }
        return new DecodedEntry(new LogEntry(term, index, data));
    }

    static int crc32c(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    /** 计算缓冲区 [from, to) 区间字节的 CRC32C。 */
    static int crc32c(ByteBuffer buffer, int from, int to) {
        ByteBuffer view = buffer.duplicate();
        view.position(from);
        view.limit(to);
        CRC32C crc = new CRC32C();
        while (view.remaining() >= 8192) {
            byte[] chunk = new byte[8192];
            view.get(chunk);
            crc.update(chunk);
        }
        byte[] tail = new byte[view.remaining()];
        view.get(tail);
        crc.update(tail);
        return (int) crc.getValue();
    }

    public record DecodedEntry(LogEntry entry) {
    }

    /** 日志文件损坏异常（恢复时用于定位截断点）。 */
    public static final class CorruptionException extends RuntimeException {
        public CorruptionException(String message) {
            super(message);
        }
    }
}
