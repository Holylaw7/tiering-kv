package io.tieringkv.storage.types;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Hash 编码（ADR-0276）：插入序字段映射，4 字节长度前缀。 */
public final class HashCodec {

    private HashCodec() {
    }

    public static byte[] encode(Map<ByteArrayKey, byte[]> fields) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(TypedValueCodec.encodeInt(fields.size()));
        for (Map.Entry<ByteArrayKey, byte[]> entry
                : fields.entrySet()) {
            writeBytes(out, entry.getKey().data());
            writeBytes(out, entry.getValue());
        }
        return out.toByteArray();
    }

    public static Map<ByteArrayKey, byte[]> decode(byte[] payload) {
        int count = TypedValueCodec.decodeInt(payload, 0);
        int offset = 4;
        Map<ByteArrayKey, byte[]> fields =
                new LinkedHashMap<>(count);
        for (int i = 0; i < count; i++) {
            byte[] field = readBytes(payload, offset);
            offset += 4 + field.length;
            byte[] value = readBytes(payload, offset);
            offset += 4 + value.length;
            fields.put(new ByteArrayKey(field), value);
        }
        return fields;
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
