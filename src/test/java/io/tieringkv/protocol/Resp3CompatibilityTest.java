package io.tieringkv.protocol;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.storage.memory.MemTable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** RESP3 协议演进（ADR-0281）。 */
class Resp3CompatibilityTest {

    @Test
    void mapEncodedWithPercent() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, new RespMap(List.of(
                new RespSimpleString("k"),
                new RespInteger(1))));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("%1\r\n+k\r\n:1\r\n");
    }

    @Test
    void setEncodedWithTilde() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, new RespSet(List.of(
                new RespInteger(1), new RespInteger(2))));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("~2\r\n:1\r\n:2\r\n");
    }

    @Test
    void doubleEncodedWithComma() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, new RespDouble(3.5));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith(",3.5\r\n");
    }

    @Test
    void bigNumberEncodedWithParen() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, new RespBigNumber(
                "3492890328409238509324850943850943825024385"));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("(3492890328409238509324850943850943825024385\r\n");
    }

    @Test
    void pushEncodedWithGreaterThan() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, new RespPush("message",
                List.of(new RespBulkString("data".getBytes(
                        StandardCharsets.UTF_8)))));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith(">2\r\n$7\r\nmessage\r\n");
    }

    @Test
    void resp2FallbackEncodesMapAsArray() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, new RespMap(List.of(
                new RespSimpleString("k"),
                new RespInteger(1))));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("*2\r\n+k\r\n:1\r\n");
    }

    @Test
    void connectionStateDefaultsResp2() {
        ConnectionProtocolState state =
                new ConnectionProtocolState();
        assertThat(state.version()).isEqualTo(RespVersion.RESP2);
        state.setVersion(RespVersion.RESP3);
        assertThat(state.isResp3()).isTrue();
    }

    @Test
    void helloReturnsServerInfo() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("hello", "3");
        assertThat(result).isInstanceOf(RespArray.class);
        RespArray array = (RespArray) result;
        assertThat(array.values()).hasSize(8);
        assertThat(((RespBulkString) array.values().get(1))
                .bytes()).isEqualTo("tiering-kv".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void helloRejectsUnsupportedVersion() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("hello", "4");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("NOPROTO");
    }

    @Test
    void hello2Accepted() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("hello", "2"))
                .isInstanceOf(RespArray.class);
    }

    @ParameterizedTest(name = "v3 type {0}")
    @MethodSource("v3Values")
    void v3EncodingMatrix(RespValue value, String prefix) {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, value);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith(prefix);
    }

    @ParameterizedTest(name = "resp2 fallback {0}")
    @MethodSource("fallbackValues")
    void resp2FallbackMatrix(RespValue value) {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, value);
        assertThat(out.readableBytes()).isPositive();
    }

    @ParameterizedTest(name = "hello {0}")
    @MethodSource("helloVersions")
    void helloVersionMatrix(String version) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("hello", version);
        assertThat(result).isInstanceOf(RespArray.class);
    }

    @ParameterizedTest(name = "hello invalid {0}")
    @MethodSource("helloInvalid")
    void helloInvalidMatrix(String version) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("hello", version))
                .isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "state {0}")
    @MethodSource("versions")
    void stateRoundTrip(RespVersion version) {
        ConnectionProtocolState state =
                new ConnectionProtocolState();
        state.setVersion(version);
        assertThat(state.version()).isEqualTo(version);
        assertThat(state.isResp3())
                .isEqualTo(version == RespVersion.RESP3);
    }

    static Stream<Arguments> v3Values() {
        return Stream.of(
                Arguments.of(new RespMap(List.of(
                        new RespInteger(1))), "%"),
                Arguments.of(new RespSet(List.of(
                        new RespInteger(1))), "~"),
                Arguments.of(new RespDouble(1.5), ","),
                Arguments.of(new RespBigNumber("123"), "("),
                Arguments.of(new RespPush("x", List.of(
                        new RespInteger(1))), ">"),
                Arguments.of(new RespMap(List.of()), "%"),
                Arguments.of(new RespSet(List.of()), "~"),
                Arguments.of(new RespDouble(-0.25), ","),
                Arguments.of(new RespBigNumber("-999"), "("),
                Arguments.of(new RespPush("pubsub", List.of()),
                        ">"));
    }

    static Stream<Arguments> fallbackValues() {
        return Stream.of(
                new RespMap(List.of(new RespInteger(1))),
                new RespSet(List.of(new RespInteger(1))),
                new RespDouble(1.5),
                new RespBigNumber("123"),
                new RespPush("x", List.of(new RespInteger(1))),
                new RespMap(List.of()),
                new RespSet(List.of()),
                new RespDouble(0.0),
                new RespBigNumber("-1"),
                new RespPush("y", List.of()))
                .map(Arguments::of);
    }

    static Stream<Arguments> helloVersions() {
        return Stream.of("2", "3").map(Arguments::of);
    }

    static Stream<Arguments> helloInvalid() {
        return Stream.of("1", "4", "abc").map(Arguments::of);
    }

    static Stream<Arguments> versions() {
        return Stream.of(RespVersion.RESP2, RespVersion.RESP3)
                .map(Arguments::of);
    }
}
