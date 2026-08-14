package io.tieringkv.storage.types;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespDouble;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多模型值编码（ADR-0320）：JSON / 时序 / 向量 + RESP3 映射。 */
class MultiModelCodecTest {

    @Test
    void jsonRoundTrip() {
        String json = "{\"name\":\"产品\",\"price\":1.5}";
        byte[] encoded = MultiModelCodec.encodeJson(json);
        assertThat(TypedValueCodec.typeOf(encoded))
                .isEqualTo(ValueType.JSON);
        assertThat(MultiModelCodec.decodeJson(encoded))
                .isEqualTo(json);
    }

    @ParameterizedTest(name = "json {0}")
    @ValueSource(strings = {"{}", "[]", "null", "\"中文\"",
            "{\"a\":[1,2,3]}"})
    void jsonVariantsRoundTrip(String json) {
        assertThat(MultiModelCodec.decodeJson(
                MultiModelCodec.encodeJson(json))).isEqualTo(json);
    }

    @Test
    void jsonRejectsNull() {
        assertThatThrownBy(() -> MultiModelCodec.encodeJson(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeSeriesRoundTripPreservesOrder() {
        List<MultiModelCodec.TimePoint> points = List.of(
                new MultiModelCodec.TimePoint(1_000L, 1.5),
                new MultiModelCodec.TimePoint(2_000L, -2.25),
                new MultiModelCodec.TimePoint(3_000L, 3.75));
        byte[] encoded = MultiModelCodec.encodeTimeSeries(points);
        assertThat(TypedValueCodec.typeOf(encoded))
                .isEqualTo(ValueType.TIME_SERIES);
        assertThat(MultiModelCodec.decodeTimeSeries(encoded))
                .containsExactlyElementsOf(points);
    }

    @Test
    void emptyTimeSeriesRoundTrip() {
        byte[] encoded = MultiModelCodec.encodeTimeSeries(List.of());
        assertThat(MultiModelCodec.decodeTimeSeries(encoded))
                .isEmpty();
    }

    @Test
    void vectorRoundTrip() {
        byte[] encoded = MultiModelCodec.encodeVector(
                new float[]{0.5f, -1.25f, 3.0f});
        assertThat(TypedValueCodec.typeOf(encoded))
                .isEqualTo(ValueType.VECTOR);
        assertThat(MultiModelCodec.decodeVector(encoded))
                .containsExactly(0.5f, -1.25f, 3.0f);
    }

    @Test
    void vectorRejectsEmptyOrNull() {
        assertThatThrownBy(() ->
                MultiModelCodec.encodeVector(new float[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MultiModelCodec.encodeVector(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decodeRejectsWrongType() {
        byte[] json = MultiModelCodec.encodeJson("{}");
        assertThatThrownBy(() ->
                MultiModelCodec.decodeVector(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VECTOR");
        assertThatThrownBy(() ->
                MultiModelCodec.decodeTimeSeries(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIME_SERIES");
    }

    @Test
    void legacyTypeBytesUnchanged() {
        byte[] hash = TypedValueCodec.encode(ValueType.HASH,
                new byte[]{1, 2, 3});
        assertThat(TypedValueCodec.isTyped(hash)).isTrue();
        assertThat(TypedValueCodec.typeOf(hash))
                .isEqualTo(ValueType.HASH);
        assertThat(TypedValueCodec.payload(hash))
                .containsExactly(1, 2, 3);
    }

    @Test
    void jsonRespIsBulkString() {
        RespValue resp = MultiModelCodec.jsonToResp(
                MultiModelCodec.encodeJson("{\"k\":1}"));
        assertThat(resp).isInstanceOf(RespBulkString.class);
        assertThat(new String(((RespBulkString) resp).bytes(),
                StandardCharsets.UTF_8)).isEqualTo("{\"k\":1}");
    }

    @Test
    void timeSeriesRespIsNestedArray() {
        RespValue resp = MultiModelCodec.timeSeriesToResp(
                MultiModelCodec.encodeTimeSeries(List.of(
                        new MultiModelCodec.TimePoint(7L, 1.5))));
        RespArray array = (RespArray) resp;
        assertThat(array.values()).hasSize(1);
        RespArray point = (RespArray) array.values().get(0);
        assertThat(point.values()).hasSize(2);
        assertThat(((RespInteger) point.values().get(0)).value())
                .isEqualTo(7);
        assertThat(((RespDouble) point.values().get(1)).value())
                .isEqualTo(1.5);
    }

    @Test
    void vectorRespIsDoubleArray() {
        RespValue resp = MultiModelCodec.vectorToResp(
                MultiModelCodec.encodeVector(
                        new float[]{1.0f, -2.0f}));
        RespArray array = (RespArray) resp;
        assertThat(array.values()).hasSize(2);
        assertThat(((RespDouble) array.values().get(0)).value())
                .isEqualTo(1.0);
        assertThat(((RespDouble) array.values().get(1)).value())
                .isEqualTo(-2.0);
    }

    @Test
    void corruptedVectorPayloadRejected() {
        byte[] encoded = MultiModelCodec.encodeVector(
                new float[]{1, 2, 3});
        // 破坏 payload 中的 dim（类型前缀后 4 字节）
        encoded[3] ^= 0x7F;
        assertThatThrownBy(() -> MultiModelCodec.decodeVector(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
