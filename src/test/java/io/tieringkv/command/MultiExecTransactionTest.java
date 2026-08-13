package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** MULTI/EXEC 事务队列（ADR-0287）。 */
class MultiExecTransactionTest {

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
    void multiReturnsOk() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context,
                () -> runner.exec("multi"));
        assertThat(result).isEqualTo(new RespSimpleString("OK"));
        assertThat(context.inMulti()).isTrue();
    }

    @Test
    void commandsQueuedDuringMulti() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            assertThat(runner.exec("set", "k", "v"))
                    .isEqualTo(new RespSimpleString("QUEUED"));
            assertThat(runner.exec("get", "k"))
                    .isEqualTo(new RespSimpleString("QUEUED"));
            return null;
        });
        assertThat(context.txnQueue()).hasSize(2);
    }

    @Test
    void execRunsQueueAndReturnsResults() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "k", "v");
            runner.exec("get", "k");
            return runner.exec("exec");
        });
        assertThat(result).isInstanceOf(RespArray.class);
        RespArray array = (RespArray) result;
        assertThat(array.values()).hasSize(2);
        assertThat(array.values().get(0)).isEqualTo(
                new RespSimpleString("OK"));
        assertThat(array.values().get(1)).isEqualTo(
                new RespBulkString("v".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(context.inMulti()).isFalse();
    }

    @Test
    void execWithoutMultiError() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context,
                () -> runner.exec("exec"));
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("EXEC without MULTI");
    }

    @Test
    void discardClearsQueue() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "k", "v");
            return runner.exec("discard");
        });
        assertThat(result).isEqualTo(new RespSimpleString("OK"));
        assertThat(context.inMulti()).isFalse();
        assertThat(context.txnQueue()).isEmpty();
    }

    @Test
    void discardWithoutMultiError() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context,
                () -> runner.exec("discard"));
        assertThat(result).isInstanceOf(RespError.class);
    }

    @Test
    void nestedMultiError() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            return runner.exec("multi");
        });
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("nested");
    }

    @Test
    void watchReturnsOk() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("watch", "k"))
                .isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void watchWithoutArgsError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("watch"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void execWritesActuallyApplied() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "a", "1");
            runner.exec("incr", "a");
            runner.exec("exec");
            return null;
        });
        assertThat(runner.exec("get", "a")).isEqualTo(
                new RespBulkString("2".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void discardDoesNotApply() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "a", "1");
            runner.exec("discard");
            return null;
        });
        assertThat(runner.exec("exists", "a")).isEqualTo(
                new RespInteger(0));
    }

    @ParameterizedTest(name = "queue size {0}")
    @MethodSource("queueSizes")
    void execResultCountMatchesQueue(int size) {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            for (int i = 0; i < size; i++) {
                runner.exec("set", "k" + i, "v" + i);
            }
            return runner.exec("exec");
        });
        assertThat(((RespArray) result).values()).hasSize(size);
    }

    @ParameterizedTest(name = "commands {0}")
    @MethodSource("commandSequences")
    void queuedCommandsReturnQueued(String command,
                                    Object[] args) {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            return runner.exec(command, args);
        });
        assertThat(result).isEqualTo(
                new RespSimpleString("QUEUED"));
    }

    static Stream<Arguments> queueSizes() {
        return Stream.of(1, 2, 5, 10).map(Arguments::of);
    }

    static Stream<Arguments> commandSequences() {
        return Stream.of(
                Arguments.of("set", new Object[]{"k", "v"}),
                Arguments.of("get", new Object[]{"k"}),
                Arguments.of("incr", new Object[]{"k"}),
                Arguments.of("hset", new Object[]{"h", "f",
                        "v"}),
                Arguments.of("rpush", new Object[]{"l", "v"}),
                Arguments.of("sadd", new Object[]{"s", "m"}),
                Arguments.of("zadd", new Object[]{"z", "1",
                        "m"}),
                Arguments.of("expire", new Object[]{"k", "10"}),
                Arguments.of("mset", new Object[]{"a", "1",
                        "b", "2"}),
                Arguments.of("mget", new Object[]{"a", "b"}));
    }
}
