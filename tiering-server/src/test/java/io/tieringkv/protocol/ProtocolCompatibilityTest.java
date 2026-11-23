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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** RESP2 兼容矩阵（ADR-0273）：编码/解码/回复形态/错误文本。 */
class ProtocolCompatibilityTest {

    @Test
    void integerEncodedAsColon() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, new RespInteger(42));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo(":42\r\n");
    }

    @Test
    void nilBulkEncodedAsMinusOne() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, RespNull.BULK_STRING);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("$-1\r\n");
    }

    @Test
    void nilArrayEncodedAsMinusOneArray() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, RespNull.ARRAY);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("*-1\r\n");
    }

    @Test
    void errorEncodedWithDash() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, new RespError(
                "ERR value is not an integer or out of range"));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("-ERR value is not an integer or "
                        + "out of range\r\n");
    }

    @Test
    void simpleStringEncodedWithPlus() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, new RespSimpleString("OK"));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("+OK\r\n");
    }

    @Test
    void bulkStringEncodedWithLength() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, new RespBulkString(
                "hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("$5\r\nhello\r\n");
    }

    @Test
    void arrayEncodedWithCount() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, new RespArray(List.of(
                new RespInteger(1), new RespInteger(2))));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("*2\r\n:1\r\n:2\r\n");
    }

    @Test
    void pipelineOrderPreserved() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue first = runner.exec("set", "a", "1");
        RespValue second = runner.exec("get", "a");
        RespValue third = runner.exec("del", "a");
        assertThat(first).isEqualTo(new RespSimpleString("OK"));
        assertThat(second).isEqualTo(new RespBulkString(
                "1".getBytes(StandardCharsets.UTF_8)));
        assertThat(third).isEqualTo(new RespInteger(1));
    }

    @ParameterizedTest(name = "roundtrip {0}")
    @MethodSource("roundTripValues")
    void encodeDecodeRoundTrip(RespValue value) throws Exception {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.write(out, value);
        RespValue decoded = RespDecoder.decodeSingle(out);
        assertThat(decoded).isEqualTo(value);
    }

    @ParameterizedTest(name = "error text {0}")
    @MethodSource("errorTexts")
    void errorMessagesMatchRedisFormat(String command,
                                       Object[] args,
                                       String expected) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec(command, args);
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "reply type {0}")
    @MethodSource("replyTypes")
    void commandReplyTypes(String command, Object[] args,
                           Class<?> expectedType) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        if (command.equals("get") || command.equals("strlen")
                || command.equals("incr")) {
            runner.exec("set", "k", "5");
        }
        RespValue result = runner.exec(command, args);
        assertThat(result).isInstanceOf(expectedType);
    }

    @ParameterizedTest(name = "nil vs empty {0}")
    @MethodSource("nilVsEmpty")
    void nilVsEmptySemantics(String command, Object[] args,
                             Class<?> expectedType,
                             String expectedText) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec(command, args);
        assertThat(result).isInstanceOf(expectedType);
        if (expectedText != null) {
            assertThat(new String(
                    ((RespBulkString) result).bytes(),
                    StandardCharsets.UTF_8))
                    .isEqualTo(expectedText);
        }
    }

    static Stream<Arguments> roundTripValues() {
        return Stream.of(
                new RespInteger(0),
                new RespInteger(-1),
                new RespInteger(Long.MAX_VALUE),
                new RespSimpleString("OK"),
                new RespSimpleString("PONG"),
                new RespBulkString("hello".getBytes(
                        StandardCharsets.UTF_8)),
                new RespBulkString(new byte[0]),
                RespNull.BULK_STRING,
                RespNull.ARRAY,
                new RespError("ERR boom"),
                new RespArray(List.of()),
                new RespArray(List.of(new RespInteger(1))),
                new RespArray(List.of(RespNull.BULK_STRING,
                        new RespSimpleString("x"))),
                new RespArray(List.of(new RespArray(List.of(
                        new RespInteger(7))))),
                new RespBulkString("中文value".getBytes(
                        StandardCharsets.UTF_8)))
                .map(Arguments::of);
    }

    static Stream<Arguments> errorTexts() {
        return Stream.of(
                Arguments.of("incr",
                        new Object[]{},
                        "ERR wrong number of arguments for "
                                + "'incr' command"),
                Arguments.of("set",
                        new Object[]{"k"},
                        "ERR wrong number of arguments for "
                                + "'set' command"),
                Arguments.of("bogus-cmd",
                        new Object[]{},
                        "ERR unknown command 'bogus-cmd'"),
                Arguments.of("incrby",
                        new Object[]{"k", "abc"},
                        "ERR value is not an integer or out of range"),
                Arguments.of("setnx",
                        new Object[]{"k"},
                        "ERR wrong number of arguments for "
                                + "'setnx' command"),
                Arguments.of("mset",
                        new Object[]{"a"},
                        "ERR wrong number of arguments for "
                                + "'mset' command"),
                Arguments.of("config",
                        new Object[]{"set", "nope", "1"},
                        "ERR Unsupported CONFIG parameter: nope"),
                Arguments.of("client",
                        new Object[]{"bogus"},
                        "ERR unknown subcommand 'bogus'"),
                Arguments.of("get",
                        new Object[]{},
                        "ERR wrong number of arguments for "
                                + "'get' command"),
                Arguments.of("expire",
                        new Object[]{"k", "x"},
                        "ERR value is not an integer or out of range"),
                Arguments.of("scan",
                        new Object[]{"x"},
                        "ERR value is not an integer or out of range"));
    }

    static Stream<Arguments> replyTypes() {
        return Stream.of(
                Arguments.of("get", new Object[]{"k"},
                        RespBulkString.class),
                Arguments.of("strlen", new Object[]{"k"},
                        RespInteger.class),
                Arguments.of("incr", new Object[]{"k"},
                        RespInteger.class),
                Arguments.of("del", new Object[]{"k"},
                        RespInteger.class),
                Arguments.of("exists", new Object[]{"k"},
                        RespInteger.class),
                Arguments.of("ttl", new Object[]{"k"},
                        RespInteger.class),
                Arguments.of("type", new Object[]{"k"},
                        RespSimpleString.class),
                Arguments.of("dbsize", new Object[]{},
                        RespInteger.class),
                Arguments.of("set", new Object[]{"x", "1"},
                        RespSimpleString.class),
                Arguments.of("mset", new Object[]{"x", "1"},
                        RespSimpleString.class),
                Arguments.of("mget", new Object[]{"x"},
                        RespArray.class),
                Arguments.of("scan", new Object[]{"0"},
                        RespArray.class),
                Arguments.of("config", new Object[]{"get", "*"},
                        RespArray.class),
                Arguments.of("command", new Object[]{"count"},
                        RespInteger.class),
                Arguments.of("client", new Object[]{"getname"},
                        RespNull.class));
    }

    static Stream<Arguments> nilVsEmpty() {
        return Stream.of(
                Arguments.of("get", new Object[]{"nope"},
                        RespNull.class, null),
                Arguments.of("strlen", new Object[]{"nope"},
                        RespInteger.class, null),
                Arguments.of("getrange", new Object[]{"nope",
                        "0", "1"}, RespBulkString.class, ""),
                Arguments.of("type", new Object[]{"nope"},
                        RespSimpleString.class, null),
                Arguments.of("client", new Object[]{"getname"},
                        RespNull.class, null),
                Arguments.of("command", new Object[]{"info",
                        "nope"}, RespArray.class, null),
                Arguments.of("getrange", new Object[]{"k", "5",
                        "10"}, RespBulkString.class, ""));
    }
}
