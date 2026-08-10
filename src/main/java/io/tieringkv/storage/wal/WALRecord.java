package io.tieringkv.storage.wal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAL 记录编解码（ADR-0015）：
 *
 * <pre>
 * [MAGIC 4B][VERSION 1B][TYPE 1B][TIMESTAMP 8B]
 * [KEY_LEN 4B][VALUE_LEN 4B][RECORD_VERSION 8B][TTL_MILLIS 8B]
 * [KEY nB][VALUE mB][CRC32C 8B]
 * </pre>
 */
public final class WALRecord {

    public static final int MAGIC = 0x544B5631; // "TKV1"
    public static final byte VERSION = 1;
    public static final byte TYPE_PUT = 1;
    public static final byte TYPE_DELETE = 2;
    public static final int HEADER_SIZE = 38;
    public static final int CHECKSUM_SIZE = 8;

    private WALRecord() {
    }

    public static byte[] encode(WALEntry entry) {
        byte[] key = entry.key();
        byte[] value = entry.value() == null ? new byte[0] : entry.value();
        int payloadLength = HEADER_SIZE + key.length + value.length;
        ByteBuffer buffer = ByteBuffer.allocate(payloadLength + CHECKSUM_SIZE)
                .order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.put(VERSION);
        buffer.put(entry.operation() == WALEntry.Operation.PUT ? TYPE_PUT : TYPE_DELETE);
        buffer.putLong(entry.timestamp());
        buffer.putInt(key.length);
        buffer.putInt(value.length);
        buffer.putLong(entry.version());
        buffer.putLong(entry.ttlMillis());
        buffer.put(key);
        buffer.put(value);
        long crc = ChecksumValidator.crc32c(buffer.array(), payloadLength);
        buffer.putLong(crc);
        return buffer.array();
    }

    /** 校验 magic/version/checksum 并解析为逻辑条目。 */
    public static WALEntry decode(byte[] record) {
        if (record.length < HEADER_SIZE + CHECKSUM_SIZE) {
            throw new WalCorruptionException("record too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        if (buffer.getInt() != MAGIC) {
            throw new WalCorruptionException("bad magic");
        }
        byte version = buffer.get();
        if (version != VERSION) {
            throw new WalCorruptionException("unsupported version: " + version);
        }
        byte type = buffer.get();
        long timestamp = buffer.getLong();
        int keyLength = buffer.getInt();
        int valueLength = buffer.getInt();
        if (keyLength < 0 || valueLength < 0) {
            throw new WalCorruptionException("negative length");
        }
        long recordVersion = buffer.getLong();
        long ttlMillis = buffer.getLong();

        int payloadLength = HEADER_SIZE + keyLength + valueLength;
        if (record.length != payloadLength + CHECKSUM_SIZE) {
            throw new WalCorruptionException("length mismatch");
        }
        long expected = buffer.getLong(payloadLength);
        if (!ChecksumValidator.matches(record, payloadLength, expected)) {
            throw new WalCorruptionException("checksum mismatch");
        }

        byte[] key = new byte[keyLength];
        buffer.get(key);
        byte[] value = null;
        if (type == TYPE_PUT) {
            value = new byte[valueLength];
            buffer.get(value);
        } else if (type != TYPE_DELETE || valueLength != 0) {
            throw new WalCorruptionException("bad type");
        }

        WALEntry.Operation operation = type == TYPE_PUT
                ? WALEntry.Operation.PUT
                : WALEntry.Operation.DELETE;
        return new WALEntry(operation, timestamp, key, value, ttlMillis, recordVersion);
    }
}
