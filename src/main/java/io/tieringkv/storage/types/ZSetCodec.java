package io.tieringkv.storage.types;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** ZSet 编码（ADR-0276）：score(double) + member 列表。 */
public final class ZSetCodec {

    /** 有序成员：score + member。 */
    public record Member(double score, ByteArrayKey member) {
    }

    private ZSetCodec() {
    }

    public static byte[] encode(List<Member> members) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(TypedValueCodec.encodeInt(members.size()));
        for (Member member : members) {
            long bits = Double.doubleToLongBits(member.score());
            byte[] scoreBytes = new byte[8];
            for (int i = 0; i < 8; i++) {
                scoreBytes[i] = (byte) (bits >>> (56 - i * 8));
            }
            out.writeBytes(scoreBytes);
            out.writeBytes(TypedValueCodec.encodeInt(
                    member.member().data().length));
            out.writeBytes(member.member().data());
        }
        return out.toByteArray();
    }

    public static List<Member> decode(byte[] payload) {
        int count = TypedValueCodec.decodeInt(payload, 0);
        int offset = 4;
        List<Member> members = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long bits = 0;
            for (int b = 0; b < 8; b++) {
                bits = (bits << 8) | (payload[offset + b] & 0xff);
            }
            offset += 8;
            int length = TypedValueCodec.decodeInt(payload,
                    offset);
            byte[] member = new byte[length];
            System.arraycopy(payload, offset + 4, member, 0,
                    length);
            offset += 4 + length;
            members.add(new Member(Double.longBitsToDouble(bits),
                    new ByteArrayKey(member)));
        }
        return members;
    }
}
