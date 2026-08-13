package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Stream 消费组（ADR-0300）。 */
class StreamConsumerGroupTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void xgroupCreateReturnsOk() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        assertThat(runner.exec("xgroup", "create", "s", "g",
                "$")).isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void xgroupDuplicateCreateError() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "$");
        RespValue result = runner.exec("xgroup", "create", "s",
                "g", "$");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("BUSYGROUP");
    }

    @Test
    void xgroupDestroy() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "$");
        assertThat(runner.exec("xgroup", "destroy", "s", "g"))
                .isEqualTo(new RespInteger(1));
        assertThat(runner.exec("xgroup", "destroy", "s", "g"))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void xreadgroupDeliversNewMessages() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "1");
        runner.exec("xgroup", "create", "s", "g", "$");
        runner.exec("xadd", "s", "2-1", "f", "2");
        RespValue result = runner.exec("xreadgroup", "group",
                "g", "c1", "streams", "s", ">");
        RespArray stream = (RespArray) ((RespArray) result)
                .values().get(0);
        RespArray entries = (RespArray) stream.values().get(1);
        assertThat(entries.values()).hasSize(1);
    }

    @Test
    void xreadgroupTracksPending() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "c1",
                "streams", "s", ">");
        RespValue pending = runner.exec("xpending", "s", "g");
        RespArray array = (RespArray) pending;
        assertThat(((RespInteger) array.values().get(0)).value())
                .isEqualTo(1);
    }

    @Test
    void xackRemovesPending() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "c1",
                "streams", "s", ">");
        assertThat(runner.exec("xack", "s", "g", "1-1"))
                .isEqualTo(new RespInteger(1));
        RespValue pending = runner.exec("xpending", "s", "g");
        assertThat(((RespInteger) ((RespArray) pending)
                .values().get(0)).value()).isZero();
    }

    @Test
    void xpendingUnknownGroupError() {
        TestCommandRunner runner = runner();
        RespValue result = runner.exec("xpending", "s", "nope");
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("NOGROUP");
    }

    @Test
    void groupStatePersistsAcrossValueRewrite() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "c1",
                "streams", "s", ">");
        runner.exec("xadd", "s", "2-1", "f", "v");
        runner.exec("xreadgroup", "group", "g", "c1",
                "streams", "s", ">");
        RespValue pending = runner.exec("xpending", "s", "g");
        assertThat(((RespInteger) ((RespArray) pending)
                .values().get(0)).value()).isEqualTo(2);
    }

    @Test
    void oldFormatStreamWithoutGroupsStillReads() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        assertThat(runner.exec("xlen", "s")).isEqualTo(
                new RespInteger(1));
    }

    @ParameterizedTest(name = "group count {0}")
    @MethodSource("groupCounts")
    void multipleGroupsIndependent(int groups) {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        for (int i = 0; i < groups; i++) {
            runner.exec("xgroup", "create", "s", "g" + i, "0");
            runner.exec("xreadgroup", "group", "g" + i, "c",
                    "streams", "s", ">");
        }
        for (int i = 0; i < groups; i++) {
            RespValue pending = runner.exec("xpending", "s",
                    "g" + i);
            assertThat(((RespInteger) ((RespArray) pending)
                    .values().get(0)).value()).isEqualTo(1);
        }
    }

    @ParameterizedTest(name = "ack count {0}")
    @MethodSource("ackCounts")
    void xackMultiple(int count) {
        TestCommandRunner runner = runner();
        for (int i = 1; i <= count; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "c1",
                "streams", "s", ">");
        Object[] ids = new Object[count];
        for (int i = 0; i < count; i++) {
            ids[i] = (i + 1) + "-1";
        }
        Object[] args = new Object[count + 2];
        args[0] = "s";
        args[1] = "g";
        System.arraycopy(ids, 0, args, 2, count);
        RespValue ack = runner.exec("xack", args);
        assertThat(ack).isEqualTo(new RespInteger(count));
    }

    static Stream<Arguments> groupCounts() {
        return Stream.of(1, 2, 3, 5).map(Arguments::of);
    }

    static Stream<Arguments> ackCounts() {
        return Stream.of(1, 2, 3, 4).map(Arguments::of);
    }
}
