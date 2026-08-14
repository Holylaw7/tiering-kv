package io.tieringkv.storage.types;

/**
 * 类型化值编码（ADR-0276）：字符串保持裸字节；复合类型前缀
 * TK 魔数 + 类型字节 + payload。WAL/RPC 冻结格式不变。
 */
public final class TypedValueCodec {

    private static final byte MAGIC_0 = 'T';
    private static final byte MAGIC_1 = 'K';

    private TypedValueCodec() {
    }

    public static byte[] encode(ValueType type, byte[] payload) {
        if (type == null || type == ValueType.STRING) {
            throw new IllegalArgumentException(
                    "string values are stored raw");
        }
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload required");
        }
        byte[] result = new byte[payload.length + 3];
        result[0] = MAGIC_0;
        result[1] = MAGIC_1;
        result[2] = typeByte(type);
        System.arraycopy(payload, 0, result, 3, payload.length);
        return result;
    }

    public static boolean isTyped(byte[] value) {
        return value != null && value.length >= 3
                && value[0] == MAGIC_0 && value[1] == MAGIC_1
                && typeOfByte(value[2]) != null;
    }

    public static ValueType typeOf(byte[] value) {
        return isTyped(value) ? typeOfByte(value[2])
                : ValueType.STRING;
    }

    public static byte[] payload(byte[] value) {
        if (!isTyped(value)) {
            throw new IllegalArgumentException(
                    "value is not typed");
        }
        byte[] payload = new byte[value.length - 3];
        System.arraycopy(value, 3, payload, 0, payload.length);
        return payload;
    }

    public static byte[] encodeInt(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    public static int decodeInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static byte typeByte(ValueType type) {
        return switch (type) {
            case HASH -> 1;
            case LIST -> 2;
            case SET -> 3;
            case ZSET -> 4;
            case STREAM -> 5;
            case JSON -> 6;
            case TIME_SERIES -> 7;
            case VECTOR -> 8;
            case STRING -> throw new IllegalArgumentException(
                    "string stored raw");
        };
    }

    private static ValueType typeOfByte(byte b) {
        return switch (b) {
            case 1 -> ValueType.HASH;
            case 2 -> ValueType.LIST;
            case 3 -> ValueType.SET;
            case 4 -> ValueType.ZSET;
            case 5 -> ValueType.STREAM;
            case 6 -> ValueType.JSON;
            case 7 -> ValueType.TIME_SERIES;
            case 8 -> ValueType.VECTOR;
            default -> null;
        };
    }
}
