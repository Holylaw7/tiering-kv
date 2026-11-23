package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
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

/** ZSet 命令族（ADR-0280）。 */
class ZSetCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void zaddAddsMembersAndReturnsCount() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("zadd", "z", "1", "a", "2", "b"))
                .isEqualTo(new RespInteger(2));
        assertThat(runner.exec("zadd", "z", "3", "a"))
                .isEqualTo(new RespInteger(0));
        assertThat(runner.exec("zcard", "z")).isEqualTo(
                new RespInteger(2));
    }

    @Test
    void zscoreReturnsFormattedScore() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "3.5", "a");
        assertThat(runner.exec("zscore", "z", "a")).isEqualTo(
                new RespBulkString("3.5".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("zscore", "z", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void zrangeSortedAscending() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "3", "c", "1", "a", "2", "b");
        RespArray result = (RespArray) runner.exec("zrange",
                "z", "0", "-1");
        assertThat(((RespBulkString) result.values().get(0))
                .bytes()).isEqualTo("a".getBytes(
                StandardCharsets.UTF_8));
        assertThat(((RespBulkString) result.values().get(2))
                .bytes()).isEqualTo("c".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void zrevrangeSortedDescending() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b");
        RespArray result = (RespArray) runner.exec("zrevrange",
                "z", "0", "-1");
        assertThat(((RespBulkString) result.values().get(0))
                .bytes()).isEqualTo("b".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void zrangeWithScores() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a");
        RespArray result = (RespArray) runner.exec("zrange",
                "z", "0", "-1", "withscores");
        assertThat(result.values()).hasSize(2);
        assertThat(((RespBulkString) result.values().get(1))
                .bytes()).isEqualTo("1".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void zremRemovesAndDeletesEmpty() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b");
        assertThat(runner.exec("zrem", "z", "a", "b")).isEqualTo(
                new RespInteger(2));
        assertThat(runner.exec("exists", "z")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void zincrbyIncrementsScore() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("zincrby", "z", "2.5", "a"))
                .isEqualTo(new RespBulkString("2.5".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("zincrby", "z", "1.5", "a"))
                .isEqualTo(new RespBulkString("4".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void zrangebyscoreFilters() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        RespArray result = (RespArray) runner.exec(
                "zrangebyscore", "z", "2", "+inf");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void zcountCountsInRange() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        assertThat(runner.exec("zcount", "z", "-inf", "2"))
                .isEqualTo(new RespInteger(2));
    }

    @Test
    void zrankReturnsRank() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b");
        assertThat(runner.exec("zrank", "z", "a")).isEqualTo(
                new RespInteger(0));
        assertThat(runner.exec("zrevrank", "z", "a")).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("zrank", "z", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void nanScoreRejected() {
        TestCommandRunner runner = runner();
        RespValue result = runner.exec("zadd", "z", "nan", "a");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("not a valid float");
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "s");
        assertThat(runner.exec("zadd", "k", "1", "a"))
                .isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "zadd {0}")
    @MethodSource("zaddMatrix")
    void zaddMatrix(String members, String expected) {
        TestCommandRunner runner = runner();
        String[] parts = members.split(",");
        Object[] args = new Object[parts.length + 1];
        args[0] = "z";
        System.arraycopy(parts, 0, args, 1, parts.length);
        assertThat(runner.exec("zadd", args)).isEqualTo(
                new RespInteger(Long.parseLong(expected)));
    }

    @ParameterizedTest(name = "zrange {0}")
    @CsvSource({
            "0, -1, 3",
            "0, 0, 1",
            "1, 2, 2",
            "-2, -1, 2",
            "5, 10, 0"
    })
    void zrangeMatrix(String start, String end,
                      String expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        RespArray result = (RespArray) runner.exec("zrange",
                "z", start, end);
        assertThat(result.values()).hasSize(
                Integer.parseInt(expectedSize));
    }

    @ParameterizedTest(name = "zrangebyscore {0}")
    @CsvSource({
            "-inf, +inf, 3",
            "2, 3, 2",
            "(2, 3, 1",
            "-inf, (3, 2",
            "10, +inf, 0",
            "1, 1, 1"
    })
    void zrangebyscoreMatrix(String min, String max,
                             String expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        RespArray result = (RespArray) runner.exec(
                "zrangebyscore", "z", min, max);
        assertThat(result.values()).hasSize(
                Integer.parseInt(expectedSize));
    }

    @ParameterizedTest(name = "zincrby {0}")
    @CsvSource({
            "1, 1.5",
            "1.5, 2",
            "-2, -1.5",
            "0, 0.5"
    })
    void zincrbyMatrix(String delta, String expected) {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "0.5", "m");
        assertThat(runner.exec("zincrby", "z", delta, "m"))
                .isEqualTo(new RespBulkString(expected.getBytes(
                        StandardCharsets.UTF_8)));
    }

    static Stream<Arguments> zaddMatrix() {
        return Stream.of(
                Arguments.of("1,a", "1"),
                Arguments.of("1,a,2,b", "2"),
                Arguments.of("1,a,2,b,3,c", "3"),
                Arguments.of("1,a,1,a", "1"),
                Arguments.of("5,x,6,y", "2"));
    }
}
