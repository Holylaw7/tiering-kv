package io.tieringkv.platform;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 53 边缘矩阵：接线/事务/高级命令边界行为。 */
class Phase53EdgeMatrixTest {

    private static <T> T withContext(ConnectionContext context,
                                     Supplier<T> action) {
        ConnectionContext.attach(context);
        try {
            return action.get();
        } finally {
            ConnectionContext.detach();
        }
    }

    @Test
    void hscanMissingKeyReturnsEmpty() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("hscan", "nope", "0");
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(((RespArray) ((RespArray) result)
                .values().get(1)).values()).isEmpty();
    }

    @Test
    void linsertInvalidWhereError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "l", "a");
        assertThat(runner.exec("linsert", "l", "middle", "a",
                "x")).isInstanceOf(RespError.class);
    }

    @Test
    void lmoveInvalidSideError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "l", "a");
        assertThat(runner.exec("lmove", "l", "d", "up",
                "right")).isInstanceOf(RespError.class);
    }

    @Test
    void zrangebylexWrongTypeError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "k", "s");
        assertThat(runner.exec("zrangebylex", "k", "-", "+"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void execWithArgsError() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context,
                () -> runner.exec("exec", "x"));
        assertThat(result).isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "edge {0}")
    @MethodSource("edges")
    void edgeMatrix(String edge) {
        switch (edge) {
            case "multi-without-context-ok" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("multi"))
                        .isEqualTo(new RespSimpleString("OK"));
            }
            case "queued-command-not-executed" -> {
                ConnectionContext context =
                        new ConnectionContext();
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                withContext(context, () -> {
                    runner.exec("multi");
                    runner.exec("set", "k", "v");
                    return null;
                });
                assertThat(runner.exec("exists", "k")).isEqualTo(
                        new io.tieringkv.protocol.RespInteger(0));
            }
            case "zlexcount-missing-zero" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zlexcount", "nope",
                        "-", "+")).isEqualTo(
                        new io.tieringkv.protocol.RespInteger(0));
            }
            case "zremrangebylex-missing-zero" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zremrangebylex", "nope",
                        "-", "+")).isEqualTo(
                        new io.tieringkv.protocol.RespInteger(0));
            }
            case "rpoplpush-same-key" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("rpush", "l", "a", "b");
                runner.exec("rpoplpush", "l", "l");
                assertThat(runner.exec("llen", "l")).isEqualTo(
                        new io.tieringkv.protocol.RespInteger(2));
            }
            case "linsert-binary-pivot" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                byte[] pivot = new byte[]{0, 1, 2};
                runner.exec("rpush", "l",
                        new byte[]{0, 1, 2});
                runner.exec("linsert", "l", "after", pivot,
                        "x");
                assertThat(runner.exec("llen", "l")).isEqualTo(
                        new io.tieringkv.protocol.RespInteger(2));
            }
            case "watch-multi-keys" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("watch", "a", "b", "c"))
                        .isEqualTo(new RespSimpleString("OK"));
            }
            case "exec-after-discard-error" -> {
                ConnectionContext context =
                        new ConnectionContext();
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                RespValue result = withContext(context, () -> {
                    runner.exec("multi");
                    runner.exec("discard");
                    return runner.exec("exec");
                });
                assertThat(result).isInstanceOf(RespError.class);
            }
            case "hello-switch-then-command" -> {
                ConnectionContext context =
                        new ConnectionContext();
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                withContext(context, () -> {
                    runner.exec("hello", "3");
                    runner.exec("set", "k", "v");
                    return null;
                });
                assertThat(context.version()).isEqualTo(
                        io.tieringkv.protocol.RespVersion.RESP3);
            }
            default -> throw new AssertionError(edge);
        }
    }

    static Stream<String> edges() {
        return Stream.of("multi-without-context-ok",
                "queued-command-not-executed",
                "zlexcount-missing-zero",
                "zremrangebylex-missing-zero",
                "rpoplpush-same-key",
                "linsert-binary-pivot",
                "watch-multi-keys",
                "exec-after-discard-error",
                "hello-switch-then-command");
    }
}
