package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * 跨集群复制事件编码（ADR-0321）：定长字段 + 长度前缀 + CRC32C。
 * 与 WAL/向量索引文件的校验惯例一致。
 */
public final class ReplicationEventCodec {

    private ReplicationEventCodec() {
    }

    public static byte[] encode(ChangeEvent event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(event.type().ordinal());
        out.writeBoolean(event.deleted());
        out.writeLong(event.timestamp());
        out.writeLong(event.seq());
        writeText(out, event.txnId());
        writeText(out, event.regionId());
        writeBytes(out, event.key());
        writeNullableBytes(out, event.value());
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

    public static ChangeEvent decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 1 + 1 + 8 + 8 + 4) {
            throw new IOException("replication event too short");
        }
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(bytes));
        int typeOrdinal = in.readUnsignedByte();
        ChangeEvent.EventType[] types = ChangeEvent.EventType.values();
        if (typeOrdinal >= types.length) {
            throw new IOException("unknown event type " + typeOrdinal);
        }
        ChangeEvent.EventType type = types[typeOrdinal];
        boolean deleted = in.readBoolean();
        long timestamp = in.readLong();
        long seq = in.readLong();
        String txnId = readText(in);
        String regionId = readText(in);
        byte[] key = readBytes(in);
        byte[] value = readNullableBytes(in);
        int expectedCrc = in.readInt();
        if ((int) crc.getValue() != expectedCrc) {
            throw new IOException("replication event CRC mismatch");
        }
        return new ChangeEvent(seq, type, key, value, deleted,
                txnId, regionId, timestamp);
    }

    private static void writeText(DataOutputStream out, String value)
            throws IOException {
        byte[] bytes = (value == null ? "" : value)
                .getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readText(DataInputStream in)
            throws IOException {
        int length = in.readUnsignedShort();
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] value)
            throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] readBytes(DataInputStream in)
            throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("invalid byte length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeNullableBytes(DataOutputStream out,
                                           byte[] value)
            throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            writeBytes(out, value);
        }
    }

    private static byte[] readNullableBytes(DataInputStream in)
            throws IOException {
        int length = in.readInt();
        if (length < 0) {
            return null;
        }
        if (length > 64 * 1024 * 1024) {
            throw new IOException("invalid byte length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
