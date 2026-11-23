package io.tieringkv.command;

import io.tieringkv.operations.BlockingListNotifier;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 阻塞命令（ADR-0293）。 */
class BlockingCommandTest {

    @Test
    void blpopImmediatePop() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "l", "a");
        RespValue result = runner.exec("blpop", "l", "1");
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(((RespBulkString) ((RespArray) result)
                .values().get(1)).bytes()).isEqualTo(
                "a".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void blpopTimeoutReturnsNil() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.currentTimeMillis();
        RespValue result = runner.exec("blpop", "l", "1");
        assertThat(result).isEqualTo(RespNull.ARRAY);
        assertThat(System.currentTimeMillis() - start)
                .isGreaterThanOrEqualTo(900);
    }

    @Test
    void blpopUnblocksOnPush() throws Exception {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        Thread pusher = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(200);
                runner.exec("rpush", "l", "b");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        pusher.start();
        RespValue result = runner.exec("blpop", "l", "5");
        pusher.join(6000);
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(((RespBulkString) ((RespArray) result)
                .values().get(1)).bytes()).isEqualTo(
                "b".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void brpopPopsRight() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "l", "a", "b");
        RespValue result = runner.exec("brpop", "l", "1");
        assertThat(((RespBulkString) ((RespArray) result)
                .values().get(1)).bytes()).isEqualTo(
                "b".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void multiKeyBlpopChecksInOrder() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "second", "x");
        RespValue result = runner.exec("blpop", "first",
                "second", "1");
        assertThat(((RespBulkString) ((RespArray) result)
                .values().get(0)).bytes()).isEqualTo(
                "second".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void negativeTimeoutError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("blpop", "l", "-1");
        assertThat(result).isInstanceOf(
                io.tieringkv.protocol.RespError.class);
    }

    @Test
    void wrongArityError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(runner.exec("blpop", "l"))
                .isInstanceOf(
                        io.tieringkv.protocol.RespError.class);
    }

    @Test
    void notifierWakesWaiter() throws Exception {
        Thread waiter = new Thread(() ->
                BlockingListNotifier.awaitPush(
                        "k".getBytes(StandardCharsets.UTF_8), 0));
        waiter.start();
        TimeUnit.MILLISECONDS.sleep(100);
        BlockingListNotifier.notifyPush(
                "k".getBytes(StandardCharsets.UTF_8));
        waiter.join(3000);
        assertThat(waiter.isAlive()).isFalse();
    }

    @ParameterizedTest(name = "blocking pop {0}")
    @MethodSource("popCommands")
    void blockingPopMatrix(String command, boolean left) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "l", "a", "b");
        RespValue result = runner.exec(command, "l", "1");
        RespArray array = (RespArray) result;
        byte[] popped = ((RespBulkString) array.values()
                .get(1)).bytes();
        assertThat(popped).isEqualTo(left
                ? "a".getBytes(StandardCharsets.UTF_8)
                : "b".getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest(name = "keys {0}")
    @MethodSource("keyCounts")
    void multiKeyTimeout(int keys) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        Object[] args = new Object[keys + 1];
        for (int i = 0; i < keys; i++) {
            args[i] = "empty" + i;
        }
        args[keys] = "1";
        assertThat(runner.exec("blpop", args))
                .isEqualTo(RespNull.ARRAY);
    }

    static Stream<Arguments> popCommands() {
        return Stream.of(
                Arguments.of("blpop", true),
                Arguments.of("brpop", false));
    }

    static Stream<Arguments> keyCounts() {
        return Stream.of(1, 2, 3, 4).map(Arguments::of);
    }
}
