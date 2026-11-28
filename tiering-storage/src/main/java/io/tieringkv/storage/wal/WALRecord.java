package io.tieringkv.storage.wal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * WAL 记录编解码（ADR-0015 / v6.5.0 零分配直编扩展）：
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
    public static final byte TYPE_CLEAR_ALL = 3;
    public static final int HEADER_SIZE = 38;
    public static final int CHECKSUM_SIZE = 8;
    public static final int CLEAR_ALL_RECORD_SIZE = HEADER_SIZE + CHECKSUM_SIZE;

    private static final byte[] EMPTY = new byte[0];

    private WALRecord() {
    }

    /** 计算一条记录编码后的精确字节长度。 */
    public static int encodedSize(byte[] key, byte[] value) {
        int keyLen = key == null ? 0 : key.length;
        int valLen = value == null ? 0 : value.length;
        return HEADER_SIZE + keyLen + valLen + CHECKSUM_SIZE;
    }

    /**
     * 零堆分配直写编码（v6.5.0）：直接向复用堆外/堆内 ByteBuffer 写入单条完整记录及 CRC32C。
     * 调用方需确保 {@code dst.remaining() >= encodedSize(key, value)} 且 {@code dst.order() == BIG_ENDIAN}。
     *
     * @return 实际写入的总字节数
     */
    public static int encodeInto(
            WALEntry.Operation operation,
            long timestamp,
            byte[] key,
            byte[] value,
            long ttlMillis,
            long version,
            ByteBuffer dst) {
        byte[] effectiveKey = operation == WALEntry.Operation.CLEAR_ALL ? EMPTY : (key == null ? EMPTY : key);
        byte[] val = (operation == WALEntry.Operation.PUT && value != null) ? value : EMPTY;
        byte type = switch (operation) {
            case PUT -> TYPE_PUT;
            case DELETE -> TYPE_DELETE;
            case CLEAR_ALL -> TYPE_CLEAR_ALL;
        };
        int startPos = dst.position();
        int payloadLength = HEADER_SIZE + effectiveKey.length + val.length;
        dst.putInt(MAGIC);
        dst.put(VERSION);
        dst.put(type);
        dst.putLong(timestamp);
        dst.putInt(effectiveKey.length);
        dst.putInt(val.length);
        dst.putLong(version);
        dst.putLong(operation == WALEntry.Operation.CLEAR_ALL ? -1L : ttlMillis);
        if (effectiveKey.length > 0) {
            dst.put(effectiveKey);
        }
        if (val.length > 0) {
            dst.put(val);
        }
        long crc = ChecksumValidator.crc32c(dst, startPos, payloadLength);
        dst.putLong(crc);
        return payloadLength + CHECKSUM_SIZE;
    }

    public static byte[] encode(WALEntry entry) {
        byte[] key = entry.key();
        byte[] value = (entry.operation() == WALEntry.Operation.PUT && entry.value() != null)
                ? entry.value() : EMPTY;
        int totalLength = HEADER_SIZE + key.length + value.length + CHECKSUM_SIZE;
        byte[] out = new byte[totalLength];
        ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        encodeInto(entry.operation(), entry.timestamp(), key, value,
                entry.ttlMillis(), entry.version(), buffer);
        return out;
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
        WALEntry.Operation operation;
        if (type == TYPE_PUT) {
            value = new byte[valueLength];
            buffer.get(value);
            operation = WALEntry.Operation.PUT;
        } else if (type == TYPE_DELETE) {
            if (valueLength != 0) {
                throw new WalCorruptionException("bad delete record");
            }
            operation = WALEntry.Operation.DELETE;
        } else if (type == TYPE_CLEAR_ALL) {
            if (keyLength != 0 || valueLength != 0 || recordVersion <= 0) {
                throw new WalCorruptionException("bad clear_all record");
            }
            operation = WALEntry.Operation.CLEAR_ALL;
        } else {
            throw new WalCorruptionException("bad type");
        }

        return new WALEntry(operation, timestamp, key, value, ttlMillis, recordVersion);
    }
}
