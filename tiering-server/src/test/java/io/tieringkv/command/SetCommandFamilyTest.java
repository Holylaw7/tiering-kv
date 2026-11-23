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

/** Set 命令族（ADR-0279）。 */
class SetCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void saddAddsUniqueMembers() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("sadd", "s", "a", "b", "a"))
                .isEqualTo(new RespInteger(2));
        assertThat(runner.exec("scard", "s")).isEqualTo(
                new RespInteger(2));
    }

    @Test
    void sremRemovesMembersAndDeletesEmptySet() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "s", "a", "b");
        assertThat(runner.exec("srem", "s", "a")).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("sismember", "s", "b")).isEqualTo(
                new RespInteger(1));
        runner.exec("srem", "s", "b");
        assertThat(runner.exec("exists", "s")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void smembersReturnsAll() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "s", "a", "b", "c");
        RespArray result = (RespArray) runner.exec("smembers",
                "s");
        assertThat(result.values()).hasSize(3);
    }

    @Test
    void spopRemovesRandomMember() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "s", "a", "b", "c");
        RespValue popped = runner.exec("spop", "s");
        assertThat(popped).isInstanceOf(RespBulkString.class);
        assertThat(runner.exec("scard", "s")).isEqualTo(
                new RespInteger(2));
    }

    @Test
    void srandmemberDoesNotRemove() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "s", "a", "b", "c");
        RespValue member = runner.exec("srandmember", "s");
        assertThat(member).isInstanceOf(RespBulkString.class);
        assertThat(runner.exec("scard", "s")).isEqualTo(
                new RespInteger(3));
    }

    @Test
    void sinterReturnsIntersection() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "a", "1", "2", "3");
        runner.exec("sadd", "b", "2", "3", "4");
        RespArray result = (RespArray) runner.exec("sinter",
                "a", "b");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void sunionReturnsUnion() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "a", "1", "2");
        runner.exec("sadd", "b", "2", "3");
        RespArray result = (RespArray) runner.exec("sunion",
                "a", "b");
        assertThat(result.values()).hasSize(3);
    }

    @Test
    void sdiffReturnsDifference() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "a", "1", "2", "3");
        runner.exec("sadd", "b", "2");
        RespArray result = (RespArray) runner.exec("sdiff",
                "a", "b");
        assertThat(result.values()).hasSize(2);
    }

    @Test
    void sinterstoreWritesDestination() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "a", "1", "2");
        runner.exec("sadd", "b", "2", "3");
        assertThat(runner.exec("sinterstore", "dest", "a", "b"))
                .isEqualTo(new RespInteger(1));
        assertThat(runner.exec("type", "dest")).isEqualTo(
                new io.tieringkv.protocol.RespSimpleString("set"));
    }

    @Test
    void storeEmptyDeletesDestination() {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "a", "1");
        runner.exec("sadd", "b", "2");
        assertThat(runner.exec("sinterstore", "dest", "a", "b"))
                .isEqualTo(new RespInteger(0));
        assertThat(runner.exec("exists", "dest")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "s");
        assertThat(runner.exec("sadd", "k", "x"))
                .isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "sadd {0}")
    @MethodSource("saddMatrix")
    void saddMatrix(String[] members, String expected) {
        TestCommandRunner runner = runner();
        Object[] args = new Object[members.length + 1];
        args[0] = "s";
        System.arraycopy(members, 0, args, 1, members.length);
        assertThat(runner.exec("sadd", args)).isEqualTo(
                new RespInteger(Long.parseLong(expected)));
    }

    @ParameterizedTest(name = "set op {0}")
    @CsvSource({
            "sinter, 1",
            "sunion, 3",
            "sdiff, 1"
    })
    void setOpMatrix(String command, String expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "a", "1", "2");
        runner.exec("sadd", "b", "2", "3");
        RespArray result = (RespArray) runner.exec(command,
                "a", "b");
        assertThat(result.values()).hasSize(
                Integer.parseInt(expectedSize));
    }

    @ParameterizedTest(name = "spop count {0}")
    @MethodSource("spopCountMatrix")
    void spopCountMatrix(String count, int expectedSize) {
        TestCommandRunner runner = runner();
        runner.exec("sadd", "s", "a", "b", "c", "d");
        RespValue result = runner.exec("spop", "s", count);
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(((RespArray) result).values())
                .hasSize(expectedSize);
    }

    static Stream<Arguments> saddMatrix() {
        return Stream.of(
                Arguments.of(new String[]{"a"}, "1"),
                Arguments.of(new String[]{"a", "b"}, "2"),
                Arguments.of(new String[]{"a", "b", "c"}, "3"),
                Arguments.of(new String[]{"a", "a", "a"}, "1"),
                Arguments.of(new String[]{"x", "y", "z"}, "3"),
                Arguments.of(new String[]{"m", "m"}, "1"));
    }

    static Stream<Arguments> spopCountMatrix() {
        return Stream.of(
                Arguments.of("1", 1),
                Arguments.of("2", 2),
                Arguments.of("3", 3),
                Arguments.of("5", 4));
    }
}
