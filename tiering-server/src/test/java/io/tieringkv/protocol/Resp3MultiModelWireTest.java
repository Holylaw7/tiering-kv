package io.tieringkv.protocol;

import io.tieringkv.command.MultiModelCommand;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 多模型值 RESP3 连接级接线（ADR-0320/0283）。 */
class Resp3MultiModelWireTest {

    private final MemTable memTable = MemTable.create();

    private static <T> T withContext(ConnectionContext context,
                                     Supplier<T> action) {
        ConnectionContext.attach(context);
        try {
            return action.get();
        } finally {
            ConnectionContext.detach();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private RespValue execute(String name, String... args) {
        return new MultiModelCommand(name).execute(
                List.of(args).stream()
                        .map(Resp3MultiModelWireTest::bytes)
                        .toList(),
                memTable);
    }

    @Test
    void vectorGetEncodesNativeResp3() {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP3);
        withContext(context, () -> {
            execute("vector.set", "v", "2", "1.5", "-2");
            RespValue resp = execute("vector.get", "v");
            ByteBuf out = Unpooled.buffer();
            RespEncoder.write(out, resp, RespVersion.RESP3);
            String wire = out.toString(StandardCharsets.UTF_8);
            assertThat(wire).startsWith("*2\r\n");
            assertThat(wire).contains(",1.5\r\n");
            assertThat(wire).contains(",-2.0\r\n");
            return null;
        });
    }

    @Test
    void timeSeriesGetEncodesNativeResp3() {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP3);
        withContext(context, () -> {
            execute("ts.add", "s", "1000", "1.5");
            RespValue resp = execute("ts.get", "s");
            ByteBuf out = Unpooled.buffer();
            RespEncoder.write(out, resp, RespVersion.RESP3);
            String wire = out.toString(StandardCharsets.UTF_8);
            assertThat(wire).startsWith("*1\r\n");
            assertThat(wire).contains(":1000\r\n");
            assertThat(wire).contains(",1.5\r\n");
            return null;
        });
    }

    @Test
    void jsonGetRemainsBulkUnderResp3() {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP3);
        withContext(context, () -> {
            execute("json.set", "j", "{\"a\":1}");
            RespValue resp = execute("json.get", "j");
            ByteBuf out = Unpooled.buffer();
            RespEncoder.write(out, resp, RespVersion.RESP3);
            String wire = out.toString(StandardCharsets.UTF_8);
            assertThat(wire).startsWith("$");
            assertThat(wire).contains("{\"a\":1}");
            return null;
        });
    }

    @Test
    void vectorGetFallsBackUnderResp2() {
        ConnectionContext context = new ConnectionContext();
        context.setVersion(RespVersion.RESP2);
        withContext(context, () -> {
            execute("vector.set", "v", "1", "7.5");
            RespValue resp = execute("vector.get", "v");
            ByteBuf out = Unpooled.buffer();
            RespEncoder.write(out, resp, RespVersion.RESP2);
            String wire = out.toString(StandardCharsets.UTF_8);
            assertThat(wire).startsWith("*1\r\n");
            return null;
        });
    }
}
