package io.tieringkv.storage.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 类型化值编码（ADR-0276）。 */
class TypedValueCodecTest {

    @Test
    void stringValuesStayRaw() {
        byte[] raw = "plain".getBytes(StandardCharsets.UTF_8);
        assertThat(TypedValueCodec.isTyped(raw)).isFalse();
        assertThat(TypedValueCodec.typeOf(raw))
                .isEqualTo(ValueType.STRING);
    }

    @Test
    void typedValuesDetected() {
        byte[] hash = TypedValueCodec.encode(ValueType.HASH,
                new byte[]{1, 2, 3});
        assertThat(TypedValueCodec.isTyped(hash)).isTrue();
        assertThat(TypedValueCodec.typeOf(hash))
                .isEqualTo(ValueType.HASH);
        assertThat(TypedValueCodec.payload(hash))
                .containsExactly(1, 2, 3);
    }

    @Test
    void encodeStringRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> TypedValueCodec.encode(ValueType.STRING,
                        new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashRoundTripPreservesInsertionOrder() {
        Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
        fields.put(new ByteArrayKey("a".getBytes(
                        StandardCharsets.UTF_8)),
                "1".getBytes(StandardCharsets.UTF_8));
        fields.put(new ByteArrayKey("b".getBytes(
                        StandardCharsets.UTF_8)),
                "2".getBytes(StandardCharsets.UTF_8));
        byte[] payload = HashCodec.encode(fields);
        Map<ByteArrayKey, byte[]> decoded = HashCodec.decode(payload);
        assertThat(decoded.keySet()).containsExactly(
                new ByteArrayKey("a".getBytes(
                        StandardCharsets.UTF_8)),
                new ByteArrayKey("b".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void listRoundTrip() {
        List<byte[]> elements = List.of(
                "x".getBytes(StandardCharsets.UTF_8),
                "y".getBytes(StandardCharsets.UTF_8));
        List<byte[]> decoded = ListCodec.decode(ListCodec.encode(
                elements));
        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0)).isEqualTo(
                "x".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void setRoundTripPreservesUniqueness() {
        Set<ByteArrayKey> members = new LinkedHashSet<>();
        members.add(new ByteArrayKey("a".getBytes(
                StandardCharsets.UTF_8)));
        members.add(new ByteArrayKey("b".getBytes(
                StandardCharsets.UTF_8)));
        Set<ByteArrayKey> decoded = SetCodec.decode(
                SetCodec.encode(members));
        assertThat(decoded).hasSize(2);
    }

    @Test
    void zsetRoundTripPreservesScores() {
        List<ZSetCodec.Member> members = List.of(
                new ZSetCodec.Member(3.5,
                        new ByteArrayKey("a".getBytes(
                                StandardCharsets.UTF_8))),
                new ZSetCodec.Member(-1.25,
                        new ByteArrayKey("b".getBytes(
                                StandardCharsets.UTF_8))));
        List<ZSetCodec.Member> decoded = ZSetCodec.decode(
                ZSetCodec.encode(members));
        assertThat(decoded).hasSize(2);
        assertThat(decoded.get(0).score()).isEqualTo(3.5);
        assertThat(decoded.get(1).score()).isEqualTo(-1.25);
    }

    @Test
    void byteArrayKeyEquality() {
        assertThat(new ByteArrayKey("k".getBytes(
                StandardCharsets.UTF_8))).isEqualTo(
                new ByteArrayKey("k".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(new ByteArrayKey("k".getBytes(
                StandardCharsets.UTF_8))).isNotEqualTo(
                new ByteArrayKey("j".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @ParameterizedTest(name = "type roundtrip {0}")
    @MethodSource("typeRoundTrips")
    void typedRoundTrips(ValueType type, byte[] payload) {
        byte[] encoded = TypedValueCodec.encode(type, payload);
        assertThat(TypedValueCodec.typeOf(encoded))
                .isEqualTo(type);
        assertThat(TypedValueCodec.payload(encoded))
                .isEqualTo(payload);
    }

    @ParameterizedTest(name = "hash fields {0}")
    @MethodSource("hashMatrices")
    void hashMatrixRoundTrips(int fieldCount) {
        Map<ByteArrayKey, byte[]> fields = new LinkedHashMap<>();
        for (int i = 0; i < fieldCount; i++) {
            fields.put(new ByteArrayKey(("f" + i).getBytes(
                            StandardCharsets.UTF_8)),
                    ("v" + i).getBytes(
                            StandardCharsets.UTF_8));
        }
        Map<ByteArrayKey, byte[]> decoded = HashCodec.decode(
                HashCodec.encode(fields));
        assertThat(decoded).hasSize(fieldCount);
        assertThat(decoded.get(new ByteArrayKey(
                ("f" + (fieldCount - 1)).getBytes(
                        StandardCharsets.UTF_8)))).isEqualTo(
                ("v" + (fieldCount - 1)).getBytes(
                        StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "list size {0}")
    @MethodSource("listMatrices")
    void listMatrixRoundTrips(int size) {
        List<byte[]> elements = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            elements.add(("e" + i).getBytes(
                    StandardCharsets.UTF_8));
        }
        List<byte[]> decoded = ListCodec.decode(
                ListCodec.encode(elements));
        assertThat(decoded).hasSize(size);
    }

    @ParameterizedTest(name = "set size {0}")
    @MethodSource("setMatrices")
    void setMatrixRoundTrips(int size) {
        Set<ByteArrayKey> members = new LinkedHashSet<>();
        for (int i = 0; i < size; i++) {
            members.add(new ByteArrayKey(("m" + i).getBytes(
                    StandardCharsets.UTF_8)));
        }
        Set<ByteArrayKey> decoded = SetCodec.decode(
                SetCodec.encode(members));
        assertThat(decoded).hasSize(size);
    }

    @ParameterizedTest(name = "zset size {0}")
    @MethodSource("zsetMatrices")
    void zsetMatrixRoundTrips(int size) {
        List<ZSetCodec.Member> members = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            members.add(new ZSetCodec.Member(i * 1.5,
                    new ByteArrayKey(("z" + i).getBytes(
                            StandardCharsets.UTF_8))));
        }
        List<ZSetCodec.Member> decoded = ZSetCodec.decode(
                ZSetCodec.encode(members));
        assertThat(decoded).hasSize(size);
        assertThat(decoded.get(size - 1).score())
                .isEqualTo((size - 1) * 1.5);
    }

    static Stream<Arguments> typeRoundTrips() {
        return Stream.of(
                Arguments.of(ValueType.HASH,
                        new byte[]{1, 2, 3, 4}),
                Arguments.of(ValueType.LIST,
                        new byte[]{9, 8, 7}),
                Arguments.of(ValueType.SET, new byte[0]),
                Arguments.of(ValueType.ZSET,
                        new byte[]{0, 0, 0, 0, 0, 0, 0, 0}),
                Arguments.of(ValueType.HASH, new byte[0]),
                Arguments.of(ValueType.LIST,
                        "payload".getBytes(
                                StandardCharsets.UTF_8)));
    }

    static Stream<Arguments> hashMatrices() {
        return Stream.of(1, 2, 3, 5, 10, 20).map(Arguments::of);
    }

    static Stream<Arguments> listMatrices() {
        return Stream.of(0, 1, 4, 8, 16, 32).map(Arguments::of);
    }

    static Stream<Arguments> setMatrices() {
        return Stream.of(0, 1, 3, 7, 15, 30).map(Arguments::of);
    }

    static Stream<Arguments> zsetMatrices() {
        return Stream.of(1, 2, 5, 10, 25, 50).map(Arguments::of);
    }
}
