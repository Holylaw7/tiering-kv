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

/** 高级数据结构命令（ADR-0286）。 */
class AdvancedDataStructureCommandTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void hscanReturnsAllFields() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "a", "1", "b", "2");
        RespArray result = (RespArray) runner.exec("hscan",
                "h", "0");
        RespArray fields = (RespArray) result.values().get(1);
        assertThat(fields.values()).hasSize(4);
    }

    @Test
    void hscanMatchFilters() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "user:1", "a", "order:1", "b");
        RespArray result = (RespArray) runner.exec("hscan",
                "h", "0", "match", "user:*");
        RespArray fields = (RespArray) result.values().get(1);
        assertThat(fields.values()).hasSize(2);
    }

    @Test
    void hscanInvalidCursorError() {
        assertThat(runner().exec("hscan", "h", "x"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void linsertBeforePivot() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "c");
        assertThat(runner.exec("linsert", "l", "before", "c",
                "b")).isEqualTo(new RespInteger(3));
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "0", "-1");
        assertThat(((RespBulkString) result.values().get(1))
                .bytes()).isEqualTo("b".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void linsertAfterPivot() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "c");
        runner.exec("linsert", "l", "after", "a", "b");
        RespArray result = (RespArray) runner.exec("lrange",
                "l", "0", "-1");
        assertThat(result.values()).hasSize(3);
    }

    @Test
    void linsertPivotMissingReturnsMinusOne() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a");
        assertThat(runner.exec("linsert", "l", "before", "x",
                "b")).isEqualTo(new RespInteger(-1));
    }

    @Test
    void linsertMissingKeyReturnsZero() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("linsert", "l", "before", "x",
                "b")).isEqualTo(new RespInteger(0));
    }

    @Test
    void lmoveMovesElement() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "src", "a", "b");
        assertThat(runner.exec("lmove", "src", "dst",
                "left", "right")).isEqualTo(
                new RespBulkString("a".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("lindex", "dst", "0")).isEqualTo(
                new RespBulkString("a".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void rpoplpushMovesTail() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "src", "a", "b");
        assertThat(runner.exec("rpoplpush", "src", "dst"))
                .isEqualTo(new RespBulkString("b".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("lindex", "dst", "0")).isEqualTo(
                new RespBulkString("b".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void lmoveEmptySourceReturnsNil() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("lmove", "src", "dst",
                "left", "right")).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void zrangebylexFilters() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        RespArray result = (RespArray) runner.exec(
                "zrangebylex", "z", "[a", "[b");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void zrangebylexOpenBounds() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b");
        RespArray result = (RespArray) runner.exec(
                "zrangebylex", "z", "-", "+");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void zlexcountCountsRange() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        assertThat(runner.exec("zlexcount", "z", "-", "+"))
                .isEqualTo(new RespInteger(3));
        assertThat(runner.exec("zlexcount", "z", "[b", "[c"))
                .isEqualTo(new RespInteger(2));
    }

    @Test
    void zremrangebylexRemovesAndDeletesEmpty() {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        assertThat(runner.exec("zremrangebylex", "z",
                "[a", "[b")).isEqualTo(new RespInteger(2));
        assertThat(runner.exec("zcard", "z")).isEqualTo(
                new RespInteger(1));
        runner.exec("zremrangebylex", "z", "-", "+");
        assertThat(runner.exec("exists", "z")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void advancedWrongType() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "s");
        assertThat(runner.exec("hscan", "k", "0"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("linsert", "k", "before", "x",
                "y")).isInstanceOf(RespError.class);
        assertThat(runner.exec("zrangebylex", "k", "-", "+"))
                .isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "linsert {0}")
    @MethodSource("linsertMatrix")
    void linsertMatrix(String where, String pivot,
                       String value, String expected) {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "l", "a", "b", "c");
        assertThat(runner.exec("linsert", "l", where, pivot,
                value)).isEqualTo(new RespInteger(
                Long.parseLong(expected)));
    }

    @ParameterizedTest(name = "lmove {0}")
    @MethodSource("lmoveMatrix")
    void lmoveMatrix(String fromSide, String toSide,
                     String expected) {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "src", "a", "b");
        RespValue result = runner.exec("lmove", "src", "dst",
                fromSide, toSide);
        assertThat(result).isEqualTo(new RespBulkString(
                expected.getBytes(StandardCharsets.UTF_8)));
    }

    @ParameterizedTest(name = "zrangebylex {0}")
    @MethodSource("lexMatrix")
    void lexRangeMatrix(String min, String max,
                        String expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        RespArray result = (RespArray) runner.exec(
                "zrangebylex", "z", min, max);
        assertThat(result.values()).hasSize(
                Integer.parseInt(expectedSize));
    }

    @ParameterizedTest(name = "zlexcount {0}")
    @CsvSource({
            "-, +, 3",
            "[a, [b, 2",
            "(a, [c, 2",
            "[c, +, 1",
            "-, (a, 0"
    })
    void zlexcountMatrix(String min, String max,
                         String expected) {
        TestCommandRunner runner = runner();
        runner.exec("zadd", "z", "1", "a", "2", "b", "3", "c");
        assertThat(runner.exec("zlexcount", "z", min, max))
                .isEqualTo(new RespInteger(
                        Long.parseLong(expected)));
    }

    static Stream<Arguments> linsertMatrix() {
        return Stream.of(
                Arguments.of("before", "b", "x", "4"),
                Arguments.of("after", "b", "x", "4"),
                Arguments.of("before", "a", "x", "4"),
                Arguments.of("after", "c", "x", "4"),
                Arguments.of("before", "nope", "x", "-1"));
    }

    static Stream<Arguments> lmoveMatrix() {
        return Stream.of(
                Arguments.of("left", "right", "a"),
                Arguments.of("right", "left", "b"),
                Arguments.of("left", "left", "a"),
                Arguments.of("right", "right", "b"));
    }

    static Stream<Arguments> lexMatrix() {
        return Stream.of(
                Arguments.of("-", "+", "3"),
                Arguments.of("[a", "[b", "2"),
                Arguments.of("[b", "+", "2"),
                Arguments.of("-", "(b", "1"),
                Arguments.of("[c", "[c", "1"));
    }
}
