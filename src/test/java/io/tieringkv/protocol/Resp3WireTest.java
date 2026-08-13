package io.tieringkv.protocol;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** RESP3 连接接线（ADR-0283）。 */
class Resp3WireTest {

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
    void encoderDispatchesByVersion() {
        ByteBuf resp2 = Unpooled.buffer();
        RespEncoder.write(resp2, new RespMap(List.of(
                new RespSimpleString("k"),
                new RespInteger(1))), RespVersion.RESP2);
        assertThat(resp2.toString(StandardCharsets.UTF_8))
                .startsWith("*2\r\n");
        ByteBuf resp3 = Unpooled.buffer();
        RespEncoder.write(resp3, new RespMap(List.of(
                new RespSimpleString("k"),
                new RespInteger(1))), RespVersion.RESP3);
        assertThat(resp3.toString(StandardCharsets.UTF_8))
                .startsWith("%1\r\n");
    }

    @Test
    void hgetallReturnsMapInResp3() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("hset", "h", "a", "1", "b", "2");
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP3);
        RespValue result = withContext(context,
                () -> runner.exec("hgetall", "h"));
        assertThat(result).isInstanceOf(RespMap.class);
        assertThat(((RespMap) result).pairs()).hasSize(4);
    }

    @Test
    void hgetallReturnsArrayInResp2() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("hset", "h", "a", "1");
        ConnectionContext context = new ConnectionContext();
        RespValue result = withContext(context,
                () -> runner.exec("hgetall", "h"));
        assertThat(result).isInstanceOf(RespArray.class);
    }

    @Test
    void smembersReturnsSetInResp3() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("sadd", "s", "a", "b");
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP3);
        RespValue result = withContext(context,
                () -> runner.exec("smembers", "s"));
        assertThat(result).isInstanceOf(RespSet.class);
        assertThat(((RespSet) result).values()).hasSize(2);
    }

    @Test
    void smembersReturnsArrayInResp2() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("sadd", "s", "a");
        RespValue result = runner.exec("smembers", "s");
        assertThat(result).isInstanceOf(RespArray.class);
    }

    @Test
    void commandEngineQueuesInsideMulti() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        ConnectionContext context = new ConnectionContext();
        RespValue queued = withContext(context, () -> {
            runner.exec("multi");
            return runner.exec("set", "k", "v");
        });
        assertThat(queued).isEqualTo(new RespSimpleString(
                "QUEUED"));
    }

    @Test
    void pushEncodedByVersion() {
        RespPush push = new RespPush("message", List.of(
                new RespBulkString("ch".getBytes(
                        StandardCharsets.UTF_8)),
                new RespBulkString("m".getBytes(
                        StandardCharsets.UTF_8))));
        ByteBuf resp2 = Unpooled.buffer();
        RespEncoder.write(resp2, push, RespVersion.RESP2);
        assertThat(resp2.toString(StandardCharsets.UTF_8))
                .startsWith("*2\r\n");
        ByteBuf resp3 = Unpooled.buffer();
        RespEncoder.write(resp3, push, RespVersion.RESP3);
        assertThat(resp3.toString(StandardCharsets.UTF_8))
                .startsWith(">3\r\n");
    }

    @ParameterizedTest(name = "value {0}")
    @MethodSource("values")
    void versionDispatchMatrix(RespValue value) {
        for (RespVersion version : RespVersion.values()) {
            ByteBuf out = Unpooled.buffer();
            RespEncoder.write(out, value, version);
            assertThat(out.readableBytes()).isPositive();
        }
    }

    @ParameterizedTest(name = "version {0}")
    @MethodSource("versions")
    void helloReturnsPerVersion(RespVersion version) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        ConnectionContext context = new ConnectionContext();
        context.setVersion(version);
        RespValue result = withContext(context,
                () -> runner.exec("hello",
                        version == RespVersion.RESP3 ? "3" : "2"));
        assertThat(result).isInstanceOf(RespArray.class);
    }

    static Stream<Arguments> values() {
        return Stream.of(
                new RespInteger(1),
                new RespSimpleString("OK"),
                new RespBulkString("x".getBytes(
                        StandardCharsets.UTF_8)),
                RespNull.BULK_STRING,
                new RespError("ERR x"),
                new RespArray(List.of(new RespInteger(1))),
                new RespMap(List.of(new RespInteger(1))),
                new RespSet(List.of(new RespInteger(1))),
                new RespDouble(1.5),
                new RespBigNumber("1"),
                new RespPush("p", List.of(new RespInteger(1))))
                .map(Arguments::of);
    }

    static Stream<Arguments> versions() {
        return Stream.of(RespVersion.RESP2, RespVersion.RESP3)
                .map(Arguments::of);
    }
}
