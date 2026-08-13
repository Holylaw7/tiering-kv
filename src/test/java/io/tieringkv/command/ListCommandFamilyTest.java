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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** List 命令族（ADR-0278）。 */
class ListCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void lpushPrependsInOrder() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("lpush", "l", "a", "b", "c"))
                .isEqualTo(new RespInteger(3));
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "0", "-1");
        assertThat(((RespBulkString) result.values().get(0))
                .bytes()).isEqualTo("c".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void rpushAppendsInOrder() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b");
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "0", "-1");
        assertThat(((RespBulkString) result.values().get(1))
                .bytes()).isEqualTo("b".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void lpopRpopReturnElements() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c");
        assertThat(runner.exec("lpop", "l")).isEqualTo(
                new RespBulkString("a".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("rpop", "l")).isEqualTo(
                new RespBulkString("c".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("llen", "l")).isEqualTo(
                new RespInteger(1));
    }

    @Test
    void popEmptyReturnsNilAndDeletesKey() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a");
        runner.exec("lpop", "l");
        assertThat(runner.exec("lpop", "l")).isEqualTo(
                RespNull.BULK_STRING);
        assertThat(runner.exec("exists", "l")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void lrangeNegativeIndices() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c", "d");
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "-2", "-1");
        assertThat(result.values()).hasSize(2);
        assertThat(((RespBulkString) result.values().get(0))
                .bytes()).isEqualTo("c".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void lindexNegative() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c");
        assertThat(runner.exec("lindex", "l", "-1")).isEqualTo(
                new RespBulkString("c".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("lindex", "l", "99"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void lsetUpdatesElement() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b");
        assertThat(runner.exec("lset", "l", "1", "x"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(runner.exec("lindex", "l", "1")).isEqualTo(
                new RespBulkString("x".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void lsetOutOfRangeError() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a");
        RespValue result = runner.exec("lset", "l", "5", "x");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("index out of range");
    }

    @Test
    void lremRemovesMatching() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "x", "a", "x", "b", "x");
        assertThat(runner.exec("lrem", "l", "2", "x"))
                .isEqualTo(new RespInteger(2));
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "0", "-1");
        assertThat(result.values()).hasSize(3);
    }

    @Test
    void ltrimTrimsRange() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c", "d");
        assertThat(runner.exec("ltrim", "l", "1", "2"))
                .isEqualTo(new RespSimpleString("OK"));
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "0", "-1");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void ltrimAllDeletesKey() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b");
        runner.exec("ltrim", "l", "5", "10");
        assertThat(runner.exec("exists", "l")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "s");
        assertThat(runner.exec("lpush", "k", "x"))
                .isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "push {0}")
    @MethodSource("pushMatrix")
    void pushMatrix(String command, String[] values,
                    String expectedLength) {
        TestCommandRunner runner = runner();
        Object[] args = new Object[values.length + 1];
        args[0] = "l";
        System.arraycopy(values, 0, args, 1, values.length);
        assertThat(runner.exec(command, args)).isEqualTo(
                new RespInteger(Long.parseLong(expectedLength)));
    }

    @ParameterizedTest(name = "pop count {0}")
    @MethodSource("popCountMatrix")
    void popWithCountReturnsArray(String command,
                                  String count,
                                  int expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c");
        RespValue result = runner.exec(command, "l", count);
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(((RespArray) result).values())
                .hasSize(expectedSize);
    }

    @ParameterizedTest(name = "lrange {0}")
    @CsvSource({
            "0, -1, 4",
            "0, 1, 2",
            "1, 2, 2",
            "-2, -1, 2",
            "5, 10, 0",
            "2, 1, 0",
            "-100, 100, 4",
            "0, 0, 1"
    })
    void lrangeMatrix(String start, String end,
                      String expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c", "d");
        RespArray result = (RespArray) runner.exec("lrange",
                "l", start, end);
        assertThat(result.values()).hasSize(
                Integer.parseInt(expectedSize));
    }

    @ParameterizedTest(name = "lrem {0}")
    @CsvSource({
            "0, x, 3",
            "1, x, 1",
            "-1, x, 1",
            "2, x, 2",
            "-2, x, 2",
            "0, nope, 0"
    })
    void lremMatrix(String count, String target,
                    String expectedRemoved) {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "x", "a", "x", "b", "x");
        assertThat(runner.exec("lrem", "l", count, target))
                .isEqualTo(new RespInteger(
                        Long.parseLong(expectedRemoved)));
    }

    static Stream<Arguments> pushMatrix() {
        return Stream.of(
                Arguments.of("lpush", new String[]{"a"}, "1"),
                Arguments.of("lpush", new String[]{"a", "b"},
                        "2"),
                Arguments.of("lpush", new String[]{"a", "b", "c"},
                        "3"),
                Arguments.of("rpush", new String[]{"a"}, "1"),
                Arguments.of("rpush", new String[]{"a", "b"},
                        "2"),
                Arguments.of("rpush", new String[]{"a", "b", "c",
                        "d"}, "4"));
    }

    static Stream<Arguments> popCountMatrix() {
        return Stream.of(
                Arguments.of("lpop", "1", 1),
                Arguments.of("lpop", "2", 2),
                Arguments.of("lpop", "5", 3),
                Arguments.of("rpop", "1", 1),
                Arguments.of("rpop", "2", 2),
                Arguments.of("rpop", "5", 3));
    }
}
