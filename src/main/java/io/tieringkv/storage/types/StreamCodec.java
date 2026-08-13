package io.tieringkv.storage.types;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stream 编码（ADR-0292）：id(ms,seq) + 字段表。 */
public final class StreamCodec {

    /** 流条目。 */
    public record Entry(long ms, long seq,
                        Map<ByteArrayKey, byte[]> fields) {
        public String id() {
            return ms + "-" + seq;
        }
    }

    private StreamCodec() {
    }

    public static byte[] encode(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(TypedValueCodec.encodeInt(entries.size()));
        for (Entry entry : entries) {
            writeLong(out, entry.ms());
            writeLong(out, entry.seq());
            out.writeBytes(TypedValueCodec.encodeInt(
                    entry.fields().size()));
            for (Map.Entry<ByteArrayKey, byte[]> field
                    : entry.fields().entrySet()) {
                writeBytes(out, field.getKey().data());
                writeBytes(out, field.getValue());
            }
        }
        return out.toByteArray();
    }

    public static List<Entry> decode(byte[] payload) {
        int count = TypedValueCodec.decodeInt(payload, 0);
        int offset = 4;
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long ms = readLong(payload, offset);
            offset += 8;
            long seq = readLong(payload, offset);
            offset += 8;
            int fields = TypedValueCodec.decodeInt(payload,
                    offset);
            offset += 4;
            Map<ByteArrayKey, byte[]> map =
                    new LinkedHashMap<>(fields);
            for (int f = 0; f < fields; f++) {
                byte[] field = readBytes(payload, offset);
                offset += 4 + field.length;
                byte[] value = readBytes(payload, offset);
                offset += 4 + value.length;
                map.put(new ByteArrayKey(field), value);
            }
            entries.add(new Entry(ms, seq, map));
        }
        return entries;
    }

    private static void writeLong(ByteArrayOutputStream out,
                                  long value) {
        for (int i = 0; i < 8; i++) {
            out.write((byte) (value >>> (56 - i * 8)));
        }
    }

    private static long readLong(byte[] payload, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8)
                    | (payload[offset + i] & 0xff);
        }
        return value;
    }

    private static void writeBytes(ByteArrayOutputStream out,
                                   byte[] bytes) {
        out.writeBytes(TypedValueCodec.encodeInt(bytes.length));
        out.writeBytes(bytes);
    }

    private static byte[] readBytes(byte[] payload, int offset) {
        int length = TypedValueCodec.decodeInt(payload, offset);
        byte[] bytes = new byte[length];
        System.arraycopy(payload, offset + 4, bytes, 0, length);
        return bytes;
    }
}
