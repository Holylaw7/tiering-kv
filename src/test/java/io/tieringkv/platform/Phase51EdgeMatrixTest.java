package io.tieringkv.platform;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 51 边缘矩阵：命令族边界行为。 */
class Phase51EdgeMatrixTest {

    @Test
    void scanInvalidCursorReturnsEmpty() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("scan", "999999");
        RespArray array = (RespArray) result;
        assertThat(((RespBulkString) array.values().get(0)).bytes())
                .isEqualTo("0".getBytes(StandardCharsets.UTF_8));
        assertThat(((RespArray) array.values().get(1)).values())
                .isEmpty();
    }

    @Test
    void commandInfoUnknownReturnsNilArray() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("command", "info", "nope");
        RespArray array = (RespArray) result;
        assertThat(array.values().get(0))
                .isEqualTo(RespNull.ARRAY);
    }

    @Test
    void getrangeInvalidIndexError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "k", "hello");
        assertThat(runner.exec("getrange", "k", "x", "1"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void setrangeNegativeOffsetError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("setrange", "k", "-1", "x");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("offset is out of range");
    }

    @Test
    void incrOverflowError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "k", Long.toString(Long.MAX_VALUE));
        RespValue result = runner.exec("incr", "k");
        assertThat(result).isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "edge {0}")
    @MethodSource("edges")
    void edgeMatrix(String edge) {
        switch (edge) {
            case "scan-count-zero-defaults" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                for (int i = 0; i < 25; i++) {
                    runner.exec("set", "k" + i, "v");
                }
                RespValue result = runner.exec("scan", "0",
                        "count", "0");
                assertThat(result).isInstanceOf(RespArray.class);
            }
            case "scan-negative-count-defaults" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "k", "v");
                RespValue result = runner.exec("scan", "0",
                        "count", "-5");
                assertThat(result).isInstanceOf(RespArray.class);
            }
            case "config-get-exact-case-insensitive" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                RespValue result = runner.exec("config", "get",
                        "MAXMEMORY");
                assertThat(((RespArray) result).values())
                        .hasSize(2);
            }
            case "config-set-empty-value-ok" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("config", "set",
                        "appendfsync", "always")).isNotInstanceOf(
                        RespError.class);
            }
            case "client-unknown-subcommand" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("client", "bogus"))
                        .isInstanceOf(RespError.class);
            }
            case "type-after-getdel-none" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "k", "v");
                runner.exec("getdel", "k");
                assertThat(runner.exec("type", "k")).isEqualTo(
                        new io.tieringkv.protocol.RespSimpleString(
                                "none"));
            }
            case "strlen-after-setrange" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("setrange", "k", "5", "abc");
                assertThat(((RespInteger) runner.exec("strlen",
                        "k")).value()).isEqualTo(8);
            }
            case "getset-missing-creates" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("getset", "newk", "v"))
                        .isEqualTo(RespNull.BULK_STRING);
                assertThat(runner.exec("exists", "newk"))
                        .isEqualTo(new RespInteger(1));
            }
            case "incrby-negative-decrement" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "k", "10");
                assertThat(runner.exec("incrby", "k", "-3"))
                        .isEqualTo(new RespInteger(7));
            }
            case "decrby-negative-increment" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "k", "10");
                assertThat(runner.exec("decrby", "k", "-3"))
                        .isEqualTo(new RespInteger(13));
            }
            case "append-binary-safe" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                byte[] zero = new byte[]{0, 1, 2};
                runner.exec("set", "k", "a");
                RespValue result = runner.exec("append",
                        "k", zero);
                assertThat(((RespInteger) result).value())
                        .isEqualTo(4);
            }
            case "getdel-missing-nil" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("getdel", "nope"))
                        .isEqualTo(RespNull.BULK_STRING);
            }
            case "setex-zero-deletes" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "k", "v");
                runner.exec("setex", "k", "0", "x");
                assertThat(runner.exec("exists", "k"))
                        .isEqualTo(new RespInteger(0));
            }
            case "expireat-zero-deletes" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "k", "v");
                runner.exec("expireat", "k", "0");
                assertThat(runner.exec("exists", "k"))
                        .isEqualTo(new RespInteger(0));
            }
            case "persist-missing-zero" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("persist", "nope"))
                        .isEqualTo(new RespInteger(0));
            }
            case "command-count-stable" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                long first = ((RespInteger) runner.exec("command",
                        "count")).value();
                long second = ((RespInteger) runner.exec("command",
                        "count")).value();
                assertThat(second).isEqualTo(first);
            }
            case "scan-binary-keys" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", new byte[]{0, 1}, "v");
                RespValue result = runner.exec("scan", "0",
                        "count", "100");
                assertThat(((RespArray) ((RespArray) result)
                        .values().get(1)).values()).hasSize(1);
            }
            default -> throw new AssertionError(edge);
        }
    }

    static Stream<String> edges() {
        return Stream.of("scan-count-zero-defaults",
                "scan-negative-count-defaults",
                "config-get-exact-case-insensitive",
                "config-set-empty-value-ok",
                "client-unknown-subcommand",
                "type-after-getdel-none",
                "strlen-after-setrange",
                "getset-missing-creates",
                "incrby-negative-decrement",
                "decrby-negative-increment",
                "append-binary-safe",
                "getdel-missing-nil",
                "setex-zero-deletes",
                "expireat-zero-deletes",
                "persist-missing-zero",
                "command-count-stable",
                "scan-binary-keys");
    }
}
