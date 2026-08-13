package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** WATCH 版本守卫（ADR-0290）。 */
class WatchVersionGuardTest {

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
    void watchRecordsVersion() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "k", "v");
        withContext(context, () -> {
            runner.exec("watch", "k");
            return null;
        });
        assertThat(context.watched()).hasSize(1);
    }

    @Test
    void execSucceedsWhenVersionUnchanged() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("set", "k", "1");
            runner.exec("watch", "k");
            runner.exec("multi");
            runner.exec("set", "k", "new");
            return runner.exec("exec");
        });
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(runner.exec("get", "k")).isEqualTo(
                new io.tieringkv.protocol.RespBulkString(
                        "new".getBytes(
                                java.nio.charset.StandardCharsets
                                        .UTF_8)));
    }

    @Test
    void execAbortsWhenVersionChanged() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("set", "k", "old");
            runner.exec("watch", "k");
            runner.exec("set", "k", "concurrent");
            runner.exec("multi");
            runner.exec("set", "k", "txn");
            return runner.exec("exec");
        });
        assertThat(result).isEqualTo(RespNull.ARRAY);
        assertThat(runner.exec("get", "k")).isEqualTo(
                new io.tieringkv.protocol.RespBulkString(
                        "concurrent".getBytes(
                                java.nio.charset.StandardCharsets
                                        .UTF_8)));
    }

    @Test
    void unwatchClearsWatchSet() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("watch", "k");
            runner.exec("unwatch");
            return null;
        });
        assertThat(context.watched()).isEmpty();
    }

    @Test
    void watchMissingKeyVersionZero() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("watch", "missing");
            return null;
        });
        assertThat(context.watched().values())
                .containsExactly(0L);
    }

    @Test
    void watchWithoutArgsError() {
        assertThat(new TestCommandRunner(MemTable.create())
                .exec("watch")).isInstanceOf(RespError.class);
    }

    @Test
    void unwatchReturnsOk() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("unwatch"))
                .isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void watchMultipleKeys() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "a", "1");
        runner.exec("set", "b", "2");
        withContext(context, () -> {
            runner.exec("watch", "a", "b", "c");
            return null;
        });
        assertThat(context.watched()).hasSize(3);
    }

    @Test
    void execClearsWatchSetAfterSuccess() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("set", "k", "v");
            runner.exec("watch", "k");
            runner.exec("multi");
            runner.exec("get", "k");
            runner.exec("exec");
            return null;
        });
        assertThat(context.watched()).isEmpty();
    }

    @ParameterizedTest(name = "watch keys {0}")
    @MethodSource("keyCounts")
    void watchKeyCounts(int keys) {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        Object[] args = new Object[keys];
        for (int i = 0; i < keys; i++) {
            args[i] = "k" + i;
        }
        withContext(context, () -> {
            runner.exec("watch", args);
            return null;
        });
        assertThat(context.watched()).hasSize(keys);
    }

    @ParameterizedTest(name = "abort variant {0}")
    @MethodSource("abortVariants")
    void abortVariants(String variant) {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("set", "k", "1");
            runner.exec("watch", "k");
            switch (variant) {
                case "set" -> runner.exec("set", "k", "x");
                case "incr" -> runner.exec("incr", "k");
                case "delete" -> runner.exec("del", "k");
                case "expire" -> runner.exec("expire", "k",
                        "100");
                default -> throw new AssertionError(variant);
            }
            runner.exec("multi");
            runner.exec("set", "k", "txn");
            return runner.exec("exec");
        });
        assertThat(result).isEqualTo(RespNull.ARRAY);
    }

    @ParameterizedTest(name = "version command {0}")
    @MethodSource("versionCommands")
    void versionBumpsOnWrite(String command, Object[] args) {
        MemTable table = MemTable.create();
        TestCommandRunner runner = new TestCommandRunner(table);
        runner.exec("set", "k", "1");
        long before = table.versionOf(
                "k".getBytes(java.nio.charset.StandardCharsets
                        .UTF_8));
        runner.exec(command, args);
        long after = table.versionOf(
                "k".getBytes(java.nio.charset.StandardCharsets
                        .UTF_8));
        if ("del".equals(command)) {
            assertThat(after).isZero();
        } else {
            assertThat(after).isGreaterThan(before);
        }
    }

    static Stream<Arguments> keyCounts() {
        return Stream.of(1, 2, 3, 5, 10).map(Arguments::of);
    }

    static Stream<Arguments> abortVariants() {
        return Stream.of("set", "incr", "delete", "expire")
                .map(Arguments::of);
    }

    static Stream<Arguments> versionCommands() {
        return Stream.of(
                Arguments.of("set", new Object[]{"k", "2"}),
                Arguments.of("incr", new Object[]{"k"}),
                Arguments.of("del", new Object[]{"k"}));
    }
}
