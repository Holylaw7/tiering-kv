package io.tieringkv.platform;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 54 边缘矩阵：事务/Stream/阻塞/通知边界。 */
class Phase54EdgeMatrixTest {

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
    void watchThenDeleteAbortsExec() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("set", "k", "v");
            runner.exec("watch", "k");
            runner.exec("del", "k");
            runner.exec("multi");
            runner.exec("set", "k", "x");
            return runner.exec("exec");
        });
        assertThat(result).isEqualTo(
                io.tieringkv.protocol.RespNull.ARRAY);
    }

    @Test
    void xaddOddArgsError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("xadd", "s", "1-1", "f"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void xtrimWrongOptionError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("xtrim", "s", "minid", "1"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void xreadInvalidArgsError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("xread", "s", "0"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void streamEmptyRetained() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xtrim", "s", "maxlen", "0");
        assertThat(runner.exec("exists", "s")).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("xlen", "s")).isEqualTo(
                new RespInteger(0));
    }

    @ParameterizedTest(name = "edge {0}")
    @MethodSource("edges")
    void edgeMatrix(String edge) {
        switch (edge) {
            case "unwatch-without-watch-ok" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("unwatch"))
                        .isEqualTo(new io.tieringkv.protocol
                                .RespSimpleString("OK"));
            }
            case "watch-typed-key" -> {
                ConnectionContext context =
                        new ConnectionContext();
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("hset", "h", "f", "v");
                withContext(context, () -> {
                    runner.exec("watch", "h");
                    return null;
                });
                assertThat(context.watched()).hasSize(1);
            }
            case "exec-rollback-journal-order" -> {
                ConnectionContext context =
                        new ConnectionContext();
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("set", "b", "abc");
                withContext(context, () -> {
                    runner.exec("multi");
                    runner.exec("set", "a", "1");
                    runner.exec("incr", "b");
                    runner.exec("exec");
                    return null;
                });
                assertThat(runner.exec("exists", "a"))
                        .isEqualTo(new RespInteger(0));
            }
            case "xrange-invalid-id-error" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("xrange", "s", "x", "+"))
                        .isInstanceOf(RespError.class);
            }
            case "blpop-timeout-zero-waits" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                Thread pusher = new Thread(() -> {
                    try {
                        Thread.sleep(150);
                        runner.exec("rpush", "l", "v");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                pusher.start();
                RespValue result = runner.exec("blpop", "l",
                        "0");
                assertThat(result).isInstanceOf(RespArray.class);
            }
            case "xread-dollar-returns-empty" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("xadd", "s", "1-1", "f", "v");
                RespArray result = (RespArray) runner.exec(
                        "xread", "streams", "s", "$");
                RespArray entries = (RespArray) ((RespArray)
                        result.values().get(0)).values().get(1);
                assertThat(entries.values()).isEmpty();
            }
            case "xlen-after-xread" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("xadd", "s", "1-1", "f", "v");
                runner.exec("xread", "streams", "s", "0");
                assertThat(runner.exec("xlen", "s")).isEqualTo(
                        new RespInteger(1));
            }
            default -> throw new AssertionError(edge);
        }
    }

    static Stream<String> edges() {
        return Stream.of("unwatch-without-watch-ok",
                "watch-typed-key",
                "exec-rollback-journal-order",
                "xrange-invalid-id-error",
                "blpop-timeout-zero-waits",
                "xread-dollar-returns-empty",
                "xlen-after-xread");
    }
}
