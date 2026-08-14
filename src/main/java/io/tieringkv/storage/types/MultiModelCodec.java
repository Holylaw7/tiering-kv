package io.tieringkv.storage.types;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespDouble;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多模型值编码（ADR-0320）：JSON / 时序 / 向量 payload 编解码 + RESP3
 * 映射。payload 经由 {@link TypedValueCodec} 加 TK 类型前缀；WAL/SSTable
 * 冻结字节不变。
 */
public final class MultiModelCodec {

    /** 时序采样点。 */
    public record TimePoint(long timestampMillis, double value) {
    }

    private MultiModelCodec() {
    }

    // ---------- JSON ----------

    public static byte[] encodeJson(String json) {
        JsonValidator.validate(json);
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return TypedValueCodec.encode(ValueType.JSON, payload);
    }

    public static String decodeJson(byte[] value) {
        requireType(value, ValueType.JSON);
        return new String(TypedValueCodec.payload(value),
                StandardCharsets.UTF_8);
    }

    // ---------- TIME_SERIES ----------

    public static byte[] encodeTimeSeries(List<TimePoint> points) {
        if (points == null) {
            throw new IllegalArgumentException("points required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(points.size());
            for (TimePoint point : points) {
                out.writeLong(point.timestampMillis());
                out.writeDouble(point.value());
            }
            out.flush();
            return TypedValueCodec.encode(ValueType.TIME_SERIES,
                    bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "timeseries encode failed", e);
        }
    }

    public static List<TimePoint> decodeTimeSeries(byte[] value) {
        requireType(value, ValueType.TIME_SERIES);
        byte[] payload = TypedValueCodec.payload(value);
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(payload));
            int count = in.readInt();
            if (count < 0 || payload.length != 4 + count * 16L) {
                throw new IllegalArgumentException(
                        "invalid timeseries payload");
            }
            List<TimePoint> points = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                points.add(new TimePoint(in.readLong(),
                        in.readDouble()));
            }
            return List.copyOf(points);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "timeseries decode failed", e);
        }
    }

    // ---------- VECTOR ----------

    public static byte[] encodeVector(float[] values) {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException(
                    "vector values required");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(values.length);
            for (float value : values) {
                out.writeFloat(value);
            }
            out.flush();
            return TypedValueCodec.encode(ValueType.VECTOR,
                    bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "vector encode failed", e);
        }
    }

    public static float[] decodeVector(byte[] value) {
        requireType(value, ValueType.VECTOR);
        byte[] payload = TypedValueCodec.payload(value);
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(payload));
            int dim = in.readInt();
            if (dim < 1 || payload.length != 4 + dim * 4L) {
                throw new IllegalArgumentException(
                        "invalid vector payload");
            }
            float[] values = new float[dim];
            for (int i = 0; i < dim; i++) {
                values[i] = in.readFloat();
            }
            return values;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "vector decode failed", e);
        }
    }

    // ---------- RESP3 ----------

    /** JSON → RespBulkString（UTF-8 原样）。 */
    public static RespValue jsonToResp(byte[] value) {
        requireType(value, ValueType.JSON);
        return new RespBulkString(TypedValueCodec.payload(value));
    }

    /** TIME_SERIES → RespArray（元素 [ts, value] 嵌套数组）。 */
    public static RespValue timeSeriesToResp(byte[] value) {
        List<RespValue> items = new ArrayList<>();
        for (TimePoint point : decodeTimeSeries(value)) {
            items.add(new RespArray(List.of(
                    new RespInteger(point.timestampMillis()),
                    new RespDouble(point.value()))));
        }
        return new RespArray(items);
    }

    /** VECTOR → RespArray（RespDouble 数组）。 */
    public static RespValue vectorToResp(byte[] value) {
        List<RespValue> items = new ArrayList<>();
        for (float item : decodeVector(value)) {
            items.add(new RespDouble(item));
        }
        return new RespArray(items);
    }

    private static void requireType(byte[] value, ValueType expected) {
        if (!TypedValueCodec.isTyped(value)
                || TypedValueCodec.typeOf(value) != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " value");
        }
    }
}
