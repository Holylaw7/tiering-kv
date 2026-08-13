package io.tieringkv.storage.types;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stream 编码（ADR-0292/0300）：条目 + 消费组段（旧数据兼容）。 */
public final class StreamCodec {

    /** 流条目。 */
    public record Entry(long ms, long seq,
                        Map<ByteArrayKey, byte[]> fields) {
        public String id() {
            return ms + "-" + seq;
        }
    }

    /** 消费组（ADR-0300）。 */
    public record Group(String name, long lastMs, long lastSeq,
                        List<Pending> pending) {
    }

    /** 未确认条目。 */
    public record Pending(long ms, long seq, String consumer) {
    }

    /** 解码结果。 */
    public record Decoded(List<Entry> entries,
                          List<Group> groups) {
    }

    private StreamCodec() {
    }

    public static byte[] encode(List<Entry> entries) {
        return encode(entries, List.of());
    }

    public static byte[] encode(List<Entry> entries,
                                List<Group> groups) {
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
        out.writeBytes(TypedValueCodec.encodeInt(groups.size()));
        for (Group group : groups) {
            writeBytes(out, group.name().getBytes(
                    StandardCharsets.UTF_8));
            writeLong(out, group.lastMs());
            writeLong(out, group.lastSeq());
            out.writeBytes(TypedValueCodec.encodeInt(
                    group.pending().size()));
            for (Pending pending : group.pending()) {
                writeLong(out, pending.ms());
                writeLong(out, pending.seq());
                writeBytes(out, pending.consumer().getBytes(
                        StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    public static Decoded decodeAll(byte[] payload) {
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
        List<Group> groups = new ArrayList<>();
        if (offset < payload.length) {
            int groupCount = TypedValueCodec.decodeInt(payload,
                    offset);
            offset += 4;
            for (int g = 0; g < groupCount; g++) {
                byte[] nameBytes = readBytes(payload, offset);
                offset += 4 + nameBytes.length;
                long lastMs = readLong(payload, offset);
                offset += 8;
                long lastSeq = readLong(payload, offset);
                offset += 8;
                int pendingCount = TypedValueCodec.decodeInt(
                        payload, offset);
                offset += 4;
                List<Pending> pending = new ArrayList<>();
                for (int p = 0; p < pendingCount; p++) {
                    long pMs = readLong(payload, offset);
                    offset += 8;
                    long pSeq = readLong(payload, offset);
                    offset += 8;
                    byte[] consumer = readBytes(payload, offset);
                    offset += 4 + consumer.length;
                    pending.add(new Pending(pMs, pSeq,
                            new String(consumer,
                                    StandardCharsets.UTF_8)));
                }
                groups.add(new Group(new String(nameBytes,
                        StandardCharsets.UTF_8), lastMs, lastSeq,
                        pending));
            }
        }
        return new Decoded(entries, groups);
    }

    public static List<Entry> decode(byte[] payload) {
        return decodeAll(payload).entries();
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
