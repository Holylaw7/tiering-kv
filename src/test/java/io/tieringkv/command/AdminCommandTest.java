package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理/SCAN 命令族（ADR-0272）。 */
class AdminCommandTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void dbsizeReflectsKeys() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", "1");
        runner.exec("set", "b", "2");
        assertThat(runner.exec("dbsize")).isEqualTo(
                new RespInteger(2));
    }

    @Test
    void flushdbClearsAll() {
        TestCommandRunner runner = runner();
        runner.exec("mset", "a", "1", "b", "2", "c", "3");
        assertThat(runner.exec("flushdb")).isEqualTo(
                new RespSimpleString("OK"));
        assertThat(runner.exec("dbsize")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void flushallClearsAll() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", "1");
        assertThat(runner.exec("flushall")).isEqualTo(
                new RespSimpleString("OK"));
        assertThat(runner.exec("exists", "a")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void typeReturnsStringOrNone() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", "1");
        assertThat(runner.exec("type", "a")).isEqualTo(
                new RespSimpleString("string"));
        assertThat(runner.exec("type", "nope")).isEqualTo(
                new RespSimpleString("none"));
    }

    @Test
    void configGetReturnsPairs() {
        RespValue result = runner().exec("config", "get",
                "maxmemory");
        List<RespValue> items = ((RespArray) result).values();
        assertThat(items).hasSize(2);
        assertThat(((RespBulkString) items.get(0)).bytes())
                .isEqualTo("maxmemory".getBytes(
                        StandardCharsets.UTF_8));
    }

    @Test
    void configSetWhitelisted() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("config", "set", "maxmemory",
                "524288000")).isEqualTo(new RespSimpleString("OK"));
        RespValue result = runner.exec("config", "get",
                "maxmemory");
        assertThat(((RespBulkString) ((RespArray) result)
                .values().get(1)).bytes()).isEqualTo(
                "524288000".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void configSetUnknownRejected() {
        RespValue result = runner().exec("config", "set",
                "not-a-param", "1");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("Unsupported CONFIG parameter");
    }

    @Test
    void clientSetnameAndGetname() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("client", "setname", "cli-1"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(runner.exec("client", "getname"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void commandCountIsLarge() {
        RespValue result = runner().exec("command", "count");
        assertThat(((RespInteger) result).value())
                .isGreaterThan(35);
    }

    @ParameterizedTest(name = "scan size={0} count={1}")
    @MethodSource("scanMatrix")
    void scanEnumeratesAllKeysExactlyOnce(int size, int count) {
        TestCommandRunner runner = runner();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < size; i++) {
            String key = "key" + i;
            runner.exec("set", key, "v");
            expected.add(key);
        }
        Set<String> collected = new HashSet<>();
        long cursor = 0;
        int iterations = 0;
        do {
            RespValue result = runner.exec("scan",
                    cursor, "count", count);
            RespArray array = (RespArray) result;
            cursor = Long.parseLong(new String(
                    ((RespBulkString) array.values().get(0)).bytes(),
                    StandardCharsets.UTF_8));
            for (RespValue key : ((RespArray) array.values()
                    .get(1)).values()) {
                collected.add(new String(
                        ((RespBulkString) key).bytes(),
                        StandardCharsets.UTF_8));
            }
            iterations++;
            assertThat(iterations).isLessThan(size * 2 + 10);
        } while (cursor != 0);
        assertThat(collected).isEqualTo(expected);
    }

    @ParameterizedTest(name = "scan match {0}")
    @MethodSource("scanMatchMatrix")
    void scanMatchFilters(String pattern, int expectedCount) {
        TestCommandRunner runner = runner();
        runner.exec("set", "user:1", "v");
        runner.exec("set", "user:2", "v");
        runner.exec("set", "order:1", "v");
        long cursor = 0;
        Set<String> collected = new HashSet<>();
        do {
            RespArray result = (RespArray) runner.exec("scan",
                    cursor, "match", pattern, "count", 100);
            cursor = Long.parseLong(new String(
                    ((RespBulkString) result.values().get(0)).bytes(),
                    StandardCharsets.UTF_8));
            for (RespValue key : ((RespArray) result.values()
                    .get(1)).values()) {
                collected.add(new String(
                        ((RespBulkString) key).bytes(),
                        StandardCharsets.UTF_8));
            }
        } while (cursor != 0);
        assertThat(collected).hasSize(expectedCount);
    }

    @ParameterizedTest(name = "config get {0}")
    @MethodSource("configGetMatrix")
    void configGetPatterns(String pattern, int pairCount) {
        RespValue result = runner().exec("config", "get",
                pattern);
        assertThat(((RespArray) result).values())
                .hasSize(pairCount * 2);
    }

    @ParameterizedTest(name = "command info {0}")
    @MethodSource("commandInfoMatrix")
    void commandInfoMetadata(String command, int arity,
                             String flags) {
        RespValue result = runner().exec("command", "info",
                command);
        RespArray item = (RespArray) ((RespArray) result)
                .values().get(0);
        assertThat(((RespBulkString) item.values().get(0)).bytes())
                .isEqualTo(command.getBytes(
                        StandardCharsets.UTF_8));
        assertThat(((RespInteger) item.values().get(1)).value())
                .isEqualTo(arity);
        RespArray flagArray = (RespArray) item.values().get(2);
        assertThat(((RespBulkString) flagArray.values().get(0))
                .bytes()).isEqualTo(flags.getBytes(
                StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "admin error {0}")
    @MethodSource("errorMatrix")
    void adminErrors(String name, Object[] args) {
        RespValue result = runner().exec(name, args);
        assertThat(result).isInstanceOf(RespError.class);
    }

    static Stream<Arguments> scanMatrix() {
        Stream.Builder<Arguments> builder = Stream.builder();
        for (int size : new int[]{5, 20, 100}) {
            for (int count : new int[]{1, 5, 10}) {
                builder.add(Arguments.of(size, count));
            }
        }
        return builder.build();
    }

    static Stream<Arguments> scanMatchMatrix() {
        return Stream.of(
                Arguments.of("user:*", 2),
                Arguments.of("order:*", 1),
                Arguments.of("*:1", 2),
                Arguments.of("*", 3),
                Arguments.of("user:?", 2),
                Arguments.of("nomatch*", 0),
                Arguments.of("user:1", 1),
                Arguments.of("?rder:1", 1));
    }

    static Stream<Arguments> configGetMatrix() {
        return Stream.of(
                Arguments.of("*", 5),
                Arguments.of("maxmemory", 1),
                Arguments.of("appendfsync", 1),
                Arguments.of("max*", 2),
                Arguments.of("timeout", 1),
                Arguments.of("save", 1),
                Arguments.of("maxclients", 1));
    }

    static Stream<Arguments> commandInfoMatrix() {
        return Stream.of(
                Arguments.of("get", 2, "readonly"),
                Arguments.of("set", -3, "write"),
                Arguments.of("del", -2, "write"),
                Arguments.of("incr", 2, "write"),
                Arguments.of("mget", -2, "readonly"),
                Arguments.of("mset", -3, "write"),
                Arguments.of("expire", 3, "write"),
                Arguments.of("ttl", 2, "readonly"),
                Arguments.of("scan", -2, "readonly"),
                Arguments.of("config", -2, "admin"),
                Arguments.of("append", 3, "write"),
                Arguments.of("getset", 3, "write"),
                Arguments.of("strlen", 2, "readonly"),
                Arguments.of("dbsize", 1, "readonly"));
    }

    static Stream<Arguments> errorMatrix() {
        return Stream.of(
                Arguments.of("scan", new Object[]{}),
                Arguments.of("config", new Object[]{}),
                Arguments.of("config", new Object[]{"get"}),
                Arguments.of("client", new Object[]{"bogus"}),
                Arguments.of("command", new Object[]{"bogus"}),
                Arguments.of("dbsize", new Object[]{"x"}),
                Arguments.of("flushdb", new Object[]{"x"}),
                Arguments.of("type", new Object[]{}),
                Arguments.of("scan", new Object[]{"abc"}));
    }
}
