package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** BIT 命令族（ADR-0334）：SETBIT/GETBIT/BITCOUNT/BITPOS/BITOP。 */
class BitCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    private static long integer(RespValue value) {
        return ((RespInteger) value).value();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void setbitReturnsOldBitAndExtendsString() {
        TestCommandRunner runner = runner();
        assertThat(integer(runner.exec("setbit", "k", "7", "1")))
                .isZero();
        assertThat(integer(runner.exec("setbit", "k", "7", "1")))
                .isEqualTo(1);
        assertThat(integer(runner.exec("setbit", "k", "0", "1")))
                .isZero();
        assertThat(integer(runner.exec("getbit", "k", "7")))
                .isEqualTo(1);
        assertThat(integer(runner.exec("getbit", "k", "0")))
                .isEqualTo(1);
        assertThat(integer(runner.exec("strlen", "k"))).isEqualTo(1);
        assertThat(integer(runner.exec("setbit", "k", "16", "1")))
                .isZero();
        assertThat(integer(runner.exec("strlen", "k"))).isEqualTo(3);
    }

    @Test
    void getbitMissingKeyReturnsZero() {
        TestCommandRunner runner = runner();
        assertThat(integer(runner.exec("getbit", "nope", "0")))
                .isZero();
        assertThat(integer(runner.exec("getbit", "nope", "999")))
                .isZero();
    }

    @Test
    void setbitInvalidOffsetRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("setbit", "k", "-1", "1"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("setbit", "k", "4294967296", "1"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("setbit", "k", "notnum", "1"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void setbitNonBinaryValueRejected() {
        TestCommandRunner runner = runner();
        RespValue result = runner.exec("setbit", "k", "0", "2");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("bit is not an integer or out of range");
    }

    @Test
    void bitcountMatchesRedisDocBaseline() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "foobar");
        assertThat(integer(runner.exec("bitcount", "k")))
                .isEqualTo(26);
        assertThat(integer(runner.exec("bitcount", "k", "1", "1")))
                .isEqualTo(6);
        assertThat(integer(runner.exec(
                "bitcount", "k", "1", "1", "byte"))).isEqualTo(6);
        assertThat(integer(runner.exec(
                "bitcount", "k", "5", "30", "bit"))).isEqualTo(17);
    }

    @Test
    void bitcountNegativeRangeAndMissingKey() {
        TestCommandRunner runner = runner();
        assertThat(integer(runner.exec("bitcount", "nope")))
                .isZero();
        runner.exec("set", "k", "foobar");
        assertThat(integer(runner.exec("bitcount", "k", "-2", "-1")))
                .isEqualTo(integer(runner.exec(
                        "bitcount", "k", "4", "5")));
    }

    @Test
    void bitposMatchesRedisDocBaseline() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", new byte[]{0x00, (byte) 0xff,
                (byte) 0xf0});
        assertThat(integer(runner.exec("bitpos", "k", "1")))
                .isEqualTo(8);
        assertThat(integer(runner.exec("bitpos", "k", "0")))
                .isZero();
        assertThat(integer(runner.exec("bitpos", "k", "1", "1", "2")))
                .isEqualTo(8);
        assertThat(integer(runner.exec(
                "bitpos", "k", "1", "0", "7", "bit")))
                .isEqualTo(-1);
        assertThat(integer(runner.exec(
                "bitpos", "k", "1", "8", "15", "bit")))
                .isEqualTo(8);
    }

    @Test
    void bitposMissingKeyAndAllOnes() {
        TestCommandRunner runner = runner();
        assertThat(integer(runner.exec("bitpos", "nope", "0")))
                .isZero();
        assertThat(integer(runner.exec("bitpos", "nope", "1")))
                .isEqualTo(-1);
        runner.exec("set", "all", new byte[]{
                (byte) 0xff, (byte) 0xff});
        assertThat(integer(runner.exec("bitpos", "all", "0")))
                .isEqualTo(16);
        assertThat(integer(runner.exec(
                "bitpos", "all", "0", "0", "1")))
                .isEqualTo(-1);
    }

    @Test
    void bitopAndOrXor() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", new byte[]{0x0f, (byte) 0xf0});
        runner.exec("set", "b", new byte[]{
                (byte) 0xff, 0x00});
        assertThat(integer(runner.exec("bitop", "and", "d",
                "a", "b"))).isEqualTo(2);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{0x0f, 0x00});
        assertThat(integer(runner.exec("bitop", "or", "d",
                "a", "b"))).isEqualTo(2);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{
                (byte) 0xff, (byte) 0xf0});
        assertThat(integer(runner.exec("bitop", "xor", "d",
                "a", "b"))).isEqualTo(2);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{
                (byte) 0xf0, (byte) 0xf0});
    }

    @Test
    void bitopPadsShorterSourcesWithZeros() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", new byte[]{(byte) 0xff});
        runner.exec("set", "b", new byte[]{0x0f, 0x0f});
        assertThat(integer(runner.exec("bitop", "and", "d",
                "a", "b"))).isEqualTo(2);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{0x0f, 0x00});
        assertThat(integer(runner.exec("bitop", "or", "d",
                "a", "b"))).isEqualTo(2);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{
                (byte) 0xff, 0x0f});
    }

    @Test
    void bitopNotRequiresSingleSource() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", new byte[]{0x0f});
        assertThat(integer(runner.exec("bitop", "not", "d", "a")))
                .isEqualTo(1);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{(byte) 0xf0});
        assertThat(runner.exec("bitop", "not", "d", "a", "a"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void bitopMissingSourceTreatedAsZeroString() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", new byte[]{(byte) 0xff, 0x0f});
        assertThat(integer(runner.exec("bitop", "and", "d",
                "a", "missing"))).isEqualTo(2);
        assertThat(((RespBulkString) runner.exec("get", "d"))
                .bytes()).isEqualTo(new byte[]{0x00, 0x00});
    }

    @Test
    void wrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("rpush", "list", "x");
        assertThat(runner.exec("getbit", "list", "0"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("bitcount", "list"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("bitop", "not", "d", "list"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void setbitPreservesTtl() {
        TestCommandRunner runner = runner();
        runner.exec("setex", "k", "100", "v");
        runner.exec("setbit", "k", "0", "1");
        RespValue ttl = runner.exec("ttl", "k");
        assertThat(integer(ttl)).isBetween(1L, 100L);
    }
}
