package io.tieringkv.storage.types;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** List 编码（ADR-0276）：顺序元素，4 字节长度前缀。 */
public final class ListCodec {

    private ListCodec() {
    }

    public static byte[] encode(List<byte[]> elements) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(TypedValueCodec.encodeInt(elements.size()));
        for (byte[] element : elements) {
            writeBytes(out, element);
        }
        return out.toByteArray();
    }

    public static List<byte[]> decode(byte[] payload) {
        int count = TypedValueCodec.decodeInt(payload, 0);
        int offset = 4;
        List<byte[]> elements = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] element = readBytes(payload, offset);
            offset += 4 + element.length;
            elements.add(element);
        }
        return elements;
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
