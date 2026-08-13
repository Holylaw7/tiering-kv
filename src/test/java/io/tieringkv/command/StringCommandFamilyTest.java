package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 字符串命令族（ADR-0269）。 */
class StringCommandFamilyTest {

    private TestCommandRunner runner(MemTable table) {
        return new TestCommandRunner(table);
    }

    @Test
    void incrOnMissingKeyReturnsOne() {
        TestCommandRunner runner = runner(MemTable.create());
        assertThat(runner.exec("incr", "k")).isEqualTo(
                new RespInteger(1));
    }

    @Test
    void decrReturnsNegative() {
        TestCommandRunner runner = runner(MemTable.create());
        assertThat(runner.exec("decr", "k")).isEqualTo(
                new RespInteger(-1));
    }

    @Test
    void incrbyAppliesDelta() {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", "10");
        assertThat(runner.exec("incrby", "k", "5")).isEqualTo(
                new RespInteger(15));
    }

    @Test
    void decrbyAppliesNegativeDelta() {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", "10");
        assertThat(runner.exec("decrby", "k", "3")).isEqualTo(
                new RespInteger(7));
    }

    @Test
    void appendCreatesMissingKey() {
        TestCommandRunner runner = runner(MemTable.create());
        assertThat(runner.exec("append", "k", "abc")).isEqualTo(
                new RespInteger(3));
        assertThat(runner.exec("get", "k")).isEqualTo(
                new RespBulkString("abc".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void strlenMissingReturnsZero() {
        TestCommandRunner runner = runner(MemTable.create());
        assertThat(runner.exec("strlen", "k")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void getsetReturnsOldAndClearsTtl() throws Exception {
        Path dir = Files.createTempDirectory("wal-getset");
        WALManager wal = new WALManager(WALConfig.defaults(dir));
        TestCommandRunner runner = new TestCommandRunner(
                new WALStorageEngine(wal, MemTable.create()));
        runner.exec("setex", "k", "100", "old");
        assertThat(runner.exec("getset", "k", "new")).isEqualTo(
                new RespBulkString("old".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("ttl", "k")).isEqualTo(
                new RespInteger(-1));
        wal.close();
    }

    @Test
    void setnxReturnsZeroWhenExists() {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", "v");
        assertThat(runner.exec("setnx", "k", "v2")).isEqualTo(
                new RespInteger(0));
        assertThat(runner.exec("get", "k")).isEqualTo(
                new RespBulkString("v".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void getdelReturnsAndDeletes() {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", "v");
        assertThat(runner.exec("getdel", "k")).isEqualTo(
                new RespBulkString("v".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("get", "k")).isEqualTo(
                RespNull.BULK_STRING);
    }

    @Test
    void setexExpiresKey() {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("setex", "k", "10", "v");
        RespValue ttl = runner.exec("ttl", "k");
        assertThat(((RespInteger) ttl).value()).isBetween(0L, 10L);
    }

    @Test
    void wrongArityRejected() {
        TestCommandRunner runner = runner(MemTable.create());
        assertThat(runner.exec("incr")).isInstanceOf(RespError.class);
        assertThat(runner.exec("append", "k")).isInstanceOf(
                RespError.class);
    }

    @ParameterizedTest(name = "initial={0} cmd={1} delta={2} "
            + "expected={3}")
    @CsvSource({
            "0, incr, 0, 1",
            "5, incr, 0, 6",
            "-5, decr, 0, -6",
            "10, incrby, 5, 15",
            "10, incrby, -5, 5",
            "10, decrby, 5, 5",
            "10, decrby, -5, 15",
            "0, incrby, 100, 100",
            "100, decrby, 100, 0",
            "1, decr, 0, 0",
            "1, decrby, 1, 0",
            "-1, incrby, -1, -2"
    })
    void integerArithmeticMatrix(String initial, String command,
                                 String delta, String expected) {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", initial);
        RespValue result;
        if ("0".equals(delta)) {
            result = runner.exec(command, "k");
        } else {
            result = runner.exec(command, "k", delta);
        }
        assertThat(((RespInteger) result).value())
                .isEqualTo(Long.parseLong(expected));
    }

    @ParameterizedTest(name = "non-integer {0}")
    @MethodSource("nonIntegerCases")
    void nonIntegerValueRejected(String command, Object[] args) {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", "abc");
        RespValue result = runner.exec(command,
                concat("k", args));
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("not an integer");
    }

    @ParameterizedTest(name = "append {0} + {1}")
    @MethodSource("appendMatrix")
    void appendMatrix(String first, String second,
                      String length) {
        TestCommandRunner runner = runner(MemTable.create());
        if (!first.isEmpty()) {
            runner.exec("set", "k", first);
        }
        RespValue result = second.isEmpty()
                ? runner.exec("append", "k")
                : runner.exec("append", "k", second);
        if (second.isEmpty()) {
            assertThat(result).isInstanceOf(RespError.class);
        } else {
            assertThat(((RespInteger) result).value())
                    .isEqualTo(Long.parseLong(length));
        }
    }

    @ParameterizedTest(name = "getrange key={0} start={1} end={2}")
    @MethodSource("getrangeMatrix")
    void getrangeMatrix(String value, String start, String end,
                        String expected) {
        TestCommandRunner runner = runner(MemTable.create());
        runner.exec("set", "k", value);
        RespValue result = runner.exec("getrange", "k", start, end);
        assertThat(((RespBulkString) result).bytes())
                .isEqualTo(expected.getBytes(
                        StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "setrange key={0} offset={1} "
            + "value={2} len={3}")
    @MethodSource("setrangeMatrix")
    void setrangeMatrix(String initial, String offset,
                        String value, String length) {
        TestCommandRunner runner = runner(MemTable.create());
        if (!initial.isEmpty()) {
            runner.exec("set", "k", initial);
        }
        if (value.isEmpty()) {
            return;
        }
        RespValue result = runner.exec("setrange", "k",
                offset, value);
        assertThat(((RespInteger) result).value())
                .isEqualTo(Long.parseLong(length));
    }

    @ParameterizedTest(name = "setnx existing={0} expected={1}")
    @CsvSource({
            "false, 1",
            "true, 0"
    })
    void setnxMatrix(String existing, String expected) {
        TestCommandRunner runner = runner(MemTable.create());
        if ("true".equals(existing)) {
            runner.exec("set", "k", "v");
        }
        assertThat(runner.exec("setnx", "k", "v")).isEqualTo(
                new RespInteger(Long.parseLong(expected)));
    }

    @ParameterizedTest(name = "wal string op {0}")
    @MethodSource("walOps")
    void walStringOpsVisible(String op) throws Exception {
        Path dir = Files.createTempDirectory("wal-string");
        WALManager wal = new WALManager(WALConfig.defaults(dir));
        TestCommandRunner runner = new TestCommandRunner(
                new WALStorageEngine(wal, MemTable.create()));
        runner.exec("set", "k", "5");
        switch (op) {
            case "incr" -> assertThat(
                    ((RespInteger) runner.exec("incr", "k")).value())
                    .isEqualTo(6);
            case "append" -> assertThat(
                    ((RespInteger) runner.exec("append", "k",
                            "x")).value()).isEqualTo(2);
            case "getset" -> assertThat(
                    ((RespBulkString) runner.exec("getset", "k",
                            "9")).bytes()).isEqualTo(
                    "5".getBytes(StandardCharsets.UTF_8));
            case "setnx" -> assertThat(
                    ((RespInteger) runner.exec("setnx", "k",
                            "x")).value()).isZero();
            case "getdel" -> assertThat(
                    ((RespBulkString) runner.exec("getdel",
                            "k")).bytes()).isEqualTo(
                    "5".getBytes(StandardCharsets.UTF_8));
            default -> throw new AssertionError(op);
        }
        wal.close();
    }

    static Stream<Arguments> nonIntegerCases() {
        return Stream.of(
                Arguments.of("incr", new Object[]{}),
                Arguments.of("decr", new Object[]{}),
                Arguments.of("incrby", new Object[]{"x"}),
                Arguments.of("decrby", new Object[]{"x"}));
    }

    static Stream<Arguments> appendMatrix() {
        return Stream.of(
                Arguments.of("a", "b", "2"),
                Arguments.of("abc", "def", "6"),
                Arguments.of("hello", "", "5"),
                Arguments.of("", "world", "5"),
                Arguments.of("x", "x", "2"),
                Arguments.of("123", "456", "6"),
                Arguments.of("a", "bcdef", "6"),
                Arguments.of("abcdef", "g", "7"));
    }

    static Stream<Arguments> getrangeMatrix() {
        return Stream.of(
                Arguments.of("hello", "0", "4", "hello"),
                Arguments.of("hello", "1", "3", "ell"),
                Arguments.of("hello", "-3", "-1", "llo"),
                Arguments.of("hello", "0", "0", "h"),
                Arguments.of("hello", "0", "99", "hello"),
                Arguments.of("hello", "99", "100", ""),
                Arguments.of("hello", "2", "1", ""),
                Arguments.of("hello", "-100", "100", "hello"),
                Arguments.of("hello", "0", "-6", ""),
                Arguments.of("hello", "-5", "-5", "h"),
                Arguments.of("hello", "4", "4", "o"),
                Arguments.of("hello", "0", "4", "hello"),
                Arguments.of("hello", "3", "3", "l"),
                Arguments.of("hello", "-1", "-1", "o"),
                Arguments.of("hello", "1", "1", "e"));
    }

    static Stream<Arguments> setrangeMatrix() {
        return Stream.of(
                Arguments.of("hello", "0", "H", "5"),
                Arguments.of("hello", "2", "XYZ", "5"),
                Arguments.of("hello", "5", "!", "6"),
                Arguments.of("", "0", "abc", "3"),
                Arguments.of("", "3", "x", "4"),
                Arguments.of("abc", "3", "def", "6"),
                Arguments.of("abc", "1", "Z", "3"),
                Arguments.of("abc", "0", "X", "3"),
                Arguments.of("x", "2", "y", "3"),
                Arguments.of("hello", "1", "x", "5"));
    }

    static Stream<Arguments> walOps() {
        return Stream.of("incr", "append", "getset", "setnx",
                        "getdel")
                .map(Arguments::of);
    }

    private static Object[] concat(Object head, Object[] tail) {
        Object[] result = new Object[tail.length + 1];
        result[0] = head;
        System.arraycopy(tail, 0, result, 1, tail.length);
        return result;
    }
}
