package io.tieringkv.protocol;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** RESP3 完整类型字节级接线（ADR-0341）：Map/Set/null + RESP2 回退。 */
class Resp3FullTypeWireTest {

    private final TestCommandRunner runner =
            new TestCommandRunner(MemTable.create());

    private static <T> T withContext(ConnectionContext context,
                                     Supplier<T> action) {
        ConnectionContext.attach(context);
        try {
            return action.get();
        } finally {
            ConnectionContext.detach();
        }
    }

    private static String wire(TestCommandRunner runner,
                               RespVersion version,
                               String name, String... args) {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(version);
        return withContext(context, () -> {
            RespValue response = runner.exec(name, (Object[]) args);
            ByteBuf out = Unpooled.buffer();
            RespEncoder.write(out, response, version);
            return out.toString(StandardCharsets.UTF_8);
        });
    }

    @Test
    void resp3NullEncodedAsUnderscore() {
        ByteBuf out = Unpooled.buffer();
        RespEncoder.writeV3(out, RespNull.BULK_STRING);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("_\r\n");
        ByteBuf array = Unpooled.buffer();
        RespEncoder.writeV3(array, RespNull.ARRAY);
        assertThat(array.toString(StandardCharsets.UTF_8))
                .isEqualTo("_\r\n");
        ByteBuf resp2 = Unpooled.buffer();
        RespEncoder.write(resp2, RespNull.BULK_STRING);
        assertThat(resp2.toString(StandardCharsets.UTF_8))
                .isEqualTo("$-1\r\n");
        ByteBuf resp2Array = Unpooled.buffer();
        RespEncoder.write(resp2Array, RespNull.ARRAY);
        assertThat(resp2Array.toString(StandardCharsets.UTF_8))
                .isEqualTo("*-1\r\n");
    }

    @Test
    void hello3ReturnsMapAndHello2Array() {
        assertThat(wire(runner, RespVersion.RESP3,
                "hello", "3")).startsWith("%4\r\n");
        assertThat(wire(runner, RespVersion.RESP3,
                "hello", "3")).contains("$7\r\nversion\r\n");
        assertThat(wire(runner, RespVersion.RESP2,
                "hello", "2")).startsWith("*8\r\n");
    }

    @Test
    void configGetReturnsMapUnderResp3() {
        assertThat(wire(runner, RespVersion.RESP3,
                "config", "get", "*")).startsWith("%5\r\n");
        assertThat(wire(runner, RespVersion.RESP2,
                "config", "get", "*")).startsWith("*10\r\n");
    }

    @Test
    void smembersReturnsSetUnderResp3() {
        runner.exec("sadd", "k", "a", "b", "c");
        assertThat(wire(runner, RespVersion.RESP3,
                "smembers", "k")).startsWith("~3\r\n");
        assertThat(wire(runner, RespVersion.RESP2,
                "smembers", "k")).startsWith("*3\r\n");
    }

    @Test
    void setOpsReturnSetUnderResp3() {
        runner.exec("sadd", "a", "x", "y");
        runner.exec("sadd", "b", "y", "z");
        assertThat(wire(runner, RespVersion.RESP3,
                "sinter", "a", "b")).startsWith("~1\r\n");
        assertThat(wire(runner, RespVersion.RESP3,
                "sunion", "a", "b")).startsWith("~3\r\n");
        assertThat(wire(runner, RespVersion.RESP3,
                "sdiff", "a", "b")).startsWith("~1\r\n");
        assertThat(wire(runner, RespVersion.RESP2,
                "sinter", "a", "b")).startsWith("*1\r\n");
        assertThat(wire(runner, RespVersion.RESP2,
                "sunion", "a", "b")).startsWith("*3\r\n");
    }

    @Test
    void spopCountReturnsSetAndSrandmemberStaysArray() {
        runner.exec("sadd", "k", "a", "b", "c");
        assertThat(wire(runner, RespVersion.RESP3,
                "spop", "k", "2")).startsWith("~2\r\n");
        runner.exec("sadd", "k2", "a", "b", "c");
        assertThat(wire(runner, RespVersion.RESP2,
                "spop", "k2", "2")).startsWith("*2\r\n");
        runner.exec("sadd", "sr", "a", "b", "c");
        assertThat(wire(runner, RespVersion.RESP3,
                "srandmember", "sr", "2")).startsWith("*2\r\n");
    }

    @Test
    void hgetallMapRegression() {
        runner.exec("hset", "h", "f1", "v1", "f2", "v2");
        assertThat(wire(runner, RespVersion.RESP3,
                "hgetall", "h")).startsWith("%2\r\n");
        assertThat(wire(runner, RespVersion.RESP2,
                "hgetall", "h")).startsWith("*4\r\n");
    }
}
