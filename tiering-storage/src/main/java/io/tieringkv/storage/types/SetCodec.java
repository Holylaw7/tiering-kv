package io.tieringkv.storage.types;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

/** Set 编码（ADR-0276）：唯一元素 + 插入序。 */
public final class SetCodec {

    private SetCodec() {
    }

    public static byte[] encode(Set<ByteArrayKey> members) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(TypedValueCodec.encodeInt(members.size()));
        for (ByteArrayKey member : members) {
            out.writeBytes(TypedValueCodec.encodeInt(
                    member.data().length));
            out.writeBytes(member.data());
        }
        return out.toByteArray();
    }

    public static Set<ByteArrayKey> decode(byte[] payload) {
        int count = TypedValueCodec.decodeInt(payload, 0);
        int offset = 4;
        Set<ByteArrayKey> members = new LinkedHashSet<>(count);
        for (int i = 0; i < count; i++) {
            int length = TypedValueCodec.decodeInt(payload,
                    offset);
            byte[] member = new byte[length];
            System.arraycopy(payload, offset + 4, member, 0,
                    length);
            offset += 4 + length;
            members.add(new ByteArrayKey(member));
        }
        return members;
    }
}
