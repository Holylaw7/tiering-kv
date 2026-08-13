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

/** Hash 命令族（ADR-0277）。 */
class HashCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void hsetAddsNewFieldsAndReturnsCount() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("hset", "h", "f1", "v1",
                "f2", "v2")).isEqualTo(new RespInteger(2));
        assertThat(runner.exec("hset", "h", "f1", "x"))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void hgetReturnsValueOrNil() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "f", "v");
        assertThat(runner.exec("hget", "h", "f")).isEqualTo(
                new RespBulkString("v".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("hget", "h", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void hdelReturnsRemovedCount() {
        TestCommandRunner runner = runner();
        runner.exec("hmset", "h", "a", "1", "b", "2", "c", "3");
        assertThat(runner.exec("hdel", "h", "a", "c", "nope"))
                .isEqualTo(new RespInteger(2));
    }

    @Test
    void hexistsAndHlen() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "a", "1");
        assertThat(runner.exec("hexists", "h", "a")).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("hexists", "h", "b")).isEqualTo(
                new RespInteger(0));
        assertThat(runner.exec("hlen", "h")).isEqualTo(
                new RespInteger(1));
    }

    @Test
    void hgetallReturnsFlatPairs() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "a", "1", "b", "2");
        RespArray result = (RespArray) runner.exec("hgetall", "h");
        assertThat(result.values()).hasSize(4);
        assertThat(((RespBulkString) result.values().get(0))
                .bytes()).isEqualTo("a".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void hmgetReturnsNilsForMissing() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "a", "1");
        RespArray result = (RespArray) runner.exec("hmget",
                "h", "a", "b");
        assertThat(result.values()).containsExactly(
                new RespBulkString("1".getBytes(
                        StandardCharsets.UTF_8)),
                RespNull.BULK_STRING);
    }

    @Test
    void hincrbyAtomicAndCreatesField() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("hincrby", "h", "c", "5"))
                .isEqualTo(new RespInteger(5));
        runner.exec("hset", "h", "c", "10");
        assertThat(runner.exec("hincrby", "h", "c", "-3"))
                .isEqualTo(new RespInteger(7));
    }

    @Test
    void hsetnxOnlySetsMissing() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("hsetnx", "h", "a", "1"))
                .isEqualTo(new RespInteger(1));
        assertThat(runner.exec("hsetnx", "h", "a", "2"))
                .isEqualTo(new RespInteger(0));
        assertThat(runner.exec("hget", "h", "a")).isEqualTo(
                new RespBulkString("1".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "string");
        RespValue result = runner.exec("hset", "k", "f", "v");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("WRONGTYPE");
    }

    @Test
    void hkeysHvalsOrder() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "a", "1", "b", "2");
        RespArray keys = (RespArray) runner.exec("hkeys", "h");
        assertThat(((RespBulkString) keys.values().get(0))
                .bytes()).isEqualTo("a".getBytes(
                StandardCharsets.UTF_8));
        RespArray values = (RespArray) runner.exec("hvals", "h");
        assertThat(((RespBulkString) values.values().get(1))
                .bytes()).isEqualTo("2".getBytes(
                StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "hset {0}")
    @MethodSource("hsetMatrix")
    void hsetMatrix(int pairs, int expectedNew) {
        TestCommandRunner runner = runner();
        Object[] args = new Object[pairs * 2 + 1];
        args[0] = "h";
        for (int i = 0; i < pairs; i++) {
            args[i * 2 + 1] = "f" + i;
            args[i * 2 + 2] = "v" + i;
        }
        assertThat(runner.exec("hset", args)).isEqualTo(
                new RespInteger(expectedNew));
    }

    @ParameterizedTest(name = "hincrby {0} + {1}")
    @CsvSource({
            "0, 5, 5",
            "10, 5, 15",
            "10, -3, 7",
            "-5, -5, -10",
            "0, 0, 0",
            "100, 100, 200"
    })
    void hincrbyMatrix(String initial, String delta,
                       String expected) {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "c", initial);
        assertThat(runner.exec("hincrby", "h", "c", delta))
                .isEqualTo(new RespInteger(
                        Long.parseLong(expected)));
    }

    @ParameterizedTest(name = "hash error {0}")
    @MethodSource("errorMatrix")
    void errorMatrix(String command, Object[] args) {
        assertThat(runner().exec(command, args))
                .isInstanceOf(RespError.class);
    }

    static Stream<Arguments> hsetMatrix() {
        return Stream.of(
                Arguments.of(1, 1),
                Arguments.of(2, 2),
                Arguments.of(3, 3),
                Arguments.of(5, 5));
    }

    static Stream<Arguments> errorMatrix() {
        return Stream.of(
                Arguments.of("hset", new Object[]{"h"}),
                Arguments.of("hget", new Object[]{"h"}),
                Arguments.of("hdel", new Object[]{"h"}),
                Arguments.of("hlen", new Object[]{}),
                Arguments.of("hmget", new Object[]{"h"}),
                Arguments.of("hincrby", new Object[]{"h", "f"}),
                Arguments.of("hsetnx", new Object[]{"h"}),
                Arguments.of("hkeys", new Object[]{}));
    }
}
