package io.tieringkv.session;

import io.tieringkv.command.RespCommand;
import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 连接级上下文（ADR-0283/0287/0288）。 */
class SessionContextTest {

    private static <T> T withContext(ConnectionContext context,
                                     java.util.function.Supplier<T>
                                             action) {
        ConnectionContext.attach(context);
        try {
            return action.get();
        } finally {
            ConnectionContext.detach();
        }
    }

    @Test
    void helloSwitchesContextVersion() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("hello", "3");
            return null;
        });
        assertThat(context.version())
                .isEqualTo(RespVersion.RESP3);
    }

    @Test
    void hello2FallsBack() {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP3);
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("hello", "2");
            return null;
        });
        assertThat(context.version())
                .isEqualTo(RespVersion.RESP2);
    }

    @Test
    void contextDefaultsResp2() {
        assertThat(new ConnectionContext().version())
                .isEqualTo(RespVersion.RESP2);
    }

    @Test
    void multiQueueEnqueuesCommands() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "k", "v");
            runner.exec("get", "k");
            return null;
        });
        assertThat(context.txnQueue()).hasSize(2);
    }

    @Test
    void discardClearsQueue() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "k", "v");
            runner.exec("discard");
            return null;
        });
        assertThat(context.inMulti()).isFalse();
        assertThat(context.txnQueue()).isEmpty();
    }

    @Test
    void cleanupResetsEverything() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("hello", "3");
            runner.exec("multi");
            runner.exec("set", "k", "v");
            return null;
        });
        context.cleanup();
        assertThat(context.version())
                .isEqualTo(RespVersion.RESP2);
        assertThat(context.inMulti()).isFalse();
        assertThat(context.txnQueue()).isEmpty();
        assertThat(context.subscriber().size()).isZero();
    }

    @Test
    void attachDetachScopesThreadLocal() {
        ConnectionContext context = new ConnectionContext();
        ConnectionContext.attach(context);
        assertThat(ConnectionContext.current())
                .isSameAs(context);
        ConnectionContext.detach();
        assertThat(ConnectionContext.current()).isNull();
    }

    @ParameterizedTest(name = "version {0}")
    @MethodSource("versions")
    void versionRoundTrip(RespVersion version) {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(version);
        assertThat(context.version()).isEqualTo(version);
    }

    @ParameterizedTest(name = "queue {0}")
    @MethodSource("queueSizes")
    void queueSizes(int size) {
        ConnectionContext context = new ConnectionContext();
        for (int i = 0; i < size; i++) {
            context.enqueue(new RespCommand("set",
                    List.of(new byte[]{1})));
        }
        assertThat(context.txnQueue()).hasSize(size);
    }

    static Stream<Arguments> versions() {
        return Stream.of(RespVersion.RESP2, RespVersion.RESP3)
                .map(Arguments::of);
    }

    static Stream<Arguments> queueSizes() {
        return Stream.of(0, 1, 5, 10, 25).map(Arguments::of);
    }
}
