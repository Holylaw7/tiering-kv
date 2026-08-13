package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 消费组高级能力（ADR-0307）。 */
class ConsumerGroupAdvancedTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void xautoclaimMovesPending() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "old",
                "streams", "s", ">");
        RespValue result = runner.exec("xautoclaim", "s", "g",
                "new", "0", "1-1");
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(((RespArray) result).values()).hasSize(1);
    }

    @Test
    void xclaimExplicitId() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "old",
                "streams", "s", ">");
        RespValue result = runner.exec("xclaim", "s", "g",
                "new", "0", "1-1");
        assertThat(((RespArray) result).values()).hasSize(1);
    }

    @Test
    void claimUnknownIdEmpty() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        RespValue result = runner.exec("xclaim", "s", "g",
                "new", "0", "9-9");
        assertThat(((RespArray) result).values()).isEmpty();
    }

    @Test
    void redeliveryIncrementsDeadLetters() {
        TestCommandRunner runner = runner();
        runner.exec("xadd", "s", "1-1", "f", "v");
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "c",
                "streams", "s", ">");
        runner.exec("xclaim", "s", "g", "c", "0", "1-1");
        runner.exec("xclaim", "s", "g", "c", "0", "1-1");
        RespValue pending = runner.exec("xpending", "s", "g");
        assertThat(((RespInteger) ((RespArray) pending)
                .values().get(0)).value()).isEqualTo(1);
    }

    @ParameterizedTest(name = "entries {0}")
    @MethodSource("entryCounts")
    void xautoclaimCount(int entries) {
        TestCommandRunner runner = runner();
        for (int i = 1; i <= entries; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "old",
                "streams", "s", ">");
        RespValue result = runner.exec("xautoclaim", "s", "g",
                "new", "0", "1-1");
        assertThat(((RespArray) result).values())
                .hasSize(entries);
    }

    static Stream<Arguments> entryCounts() {
        return Stream.of(1, 2, 3, 5).map(Arguments::of);
    }
}
