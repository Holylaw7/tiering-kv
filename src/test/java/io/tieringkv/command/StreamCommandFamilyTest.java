package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Stream 数据类型（ADR-0292）。 */
class StreamCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void xaddAutoId() {
        TestCommandRunner runner = runner();
        RespValue result = runner.exec("xadd", "s", "*",
                "f", "v");
        assertThat(result).isInstanceOf(RespBulkString.class);
        assertThat(new String(((RespBulkString) result).bytes(),
                StandardCharsets.UTF_8)).matches("\\d+-\\d+");
    }

    @Test
    void xaddExplicitId() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("xadd", "s", "1-1", "f", "v"))
                .isEqualTo(new RespBulkString("1-1".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void xaddRejectsOlderId() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "5-1", "f", "v");
        RespValue result = runner.exec("xadd", "s", "4-1",
                "f", "v");
        assertThat(result).isInstanceOf(RespError.class);
    }

    @Test
    void xlenCountsEntries() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xadd", "s", "2-1", "f", "v");
        assertThat(runner.exec("xlen", "s")).isEqualTo(
                new RespInteger(2));
    }

    @Test
    void xrangeReturnsRange() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "a", "1");
        runner.exec("xadd", "s", "2-1", "a", "2");
        runner.exec("xadd", "s", "3-1", "a", "3");
        RespArray result = (RespArray) runner.exec("xrange",
                "s", "2-1", "+");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void xtrimMaxlenRemovesOldest() {
        TestCommandRunner runner = runner();
        for (int i = 1; i <= 5; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        assertThat(runner.exec("xtrim", "s", "maxlen", "2"))
                .isEqualTo(new RespInteger(3));
        assertThat(runner.exec("xlen", "s")).isEqualTo(
                new RespInteger(2));
    }

    @Test
    void xreadAfterId() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xadd", "s", "2-1", "f", "v");
        RespArray result = (RespArray) runner.exec("xread",
                "streams", "s", "1-1");
        RespArray stream = (RespArray) result.values().get(0);
        RespArray entries = (RespArray) stream.values().get(1);
        assertThat(entries.values()).hasSize(1);
    }

    @Test
    void typeReportsStream() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        assertThat(runner.exec("type", "s")).isEqualTo(
                new io.tieringkv.protocol.RespSimpleString(
                        "stream"));
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "s");
        assertThat(runner.exec("xadd", "k", "1-1", "f", "v"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void xlenMissingZero() {
        assertThat(runner().exec("xlen", "nope")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void xreadMissingKeyEmpty() {
        RespArray result = (RespArray) runner().exec("xread",
                "streams", "nope", "0");
        RespArray entries = (RespArray) ((RespArray) result
                .values().get(0)).values().get(1);
        assertThat(entries.values()).isEmpty();
    }

    @ParameterizedTest(name = "xadd {0}")
    @MethodSource("xaddMatrix")
    void xaddMatrix(String id, String expectedPattern) {
        TestCommandRunner runner = runner();
        RespValue result = runner.exec("xadd", "s", id,
                "f", "v");
        String actual = new String(
                ((RespBulkString) result).bytes(),
                StandardCharsets.UTF_8);
        assertThat(actual).matches(expectedPattern);
    }

    @ParameterizedTest(name = "xrange {0}")
    @MethodSource("xrangeMatrix")
    void xrangeMatrix(String start, String end,
                      String expectedSize) {
        TestCommandRunner runner = runner();
        for (int i = 1; i <= 5; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        RespArray result = (RespArray) runner.exec("xrange",
                "s", start, end);
        assertThat(result.values()).hasSize(
                Integer.parseInt(expectedSize));
    }

    @ParameterizedTest(name = "xtrim {0}")
    @MethodSource("trimMatrix")
    void trimMatrix(String maxlen, String removed,
                    String remaining) {
        TestCommandRunner runner = runner();
        for (int i = 1; i <= 4; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        assertThat(runner.exec("xtrim", "s", "maxlen", maxlen))
                .isEqualTo(new RespInteger(
                        Long.parseLong(removed)));
        assertThat(runner.exec("xlen", "s")).isEqualTo(
                new RespInteger(Long.parseLong(remaining)));
    }

    static Stream<Arguments> xaddMatrix() {
        return Stream.of(
                Arguments.of("*", "\\d+-\\d+"),
                Arguments.of("1-1", "1-1"),
                Arguments.of("2-0", "2-0"),
                Arguments.of("100-5", "100-5"),
                Arguments.of("*", "\\d+-\\d+"));
    }

    static Stream<Arguments> xrangeMatrix() {
        return Stream.of(
                Arguments.of("-", "+", "5"),
                Arguments.of("1-1", "2-1", "2"),
                Arguments.of("3-1", "+", "3"),
                Arguments.of("-", "1-1", "1"),
                Arguments.of("5-1", "+", "1"));
    }

    static Stream<Arguments> trimMatrix() {
        return Stream.of(
                Arguments.of("1", "3", "1"),
                Arguments.of("2", "2", "2"),
                Arguments.of("4", "0", "4"),
                Arguments.of("10", "0", "4"));
    }
}
