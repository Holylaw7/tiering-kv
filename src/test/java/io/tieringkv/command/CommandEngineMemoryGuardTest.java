package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-083：内存压力下拒绝写命令、放行读命令，错误语义与 Redis 对齐。 */
class CommandEngineMemoryGuardTest {

    private static RespValue[] run(CommandEngine engine, String... tokens) {
        List<byte[]> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            args.add(tokens[i].getBytes(StandardCharsets.UTF_8));
        }
        RespValue[] holder = new RespValue[1];
        holder[0] = engine.execute(new RespCommand(tokens[0], args));
        return holder;
    }

    private static CommandEngine engineUnderPressure(AtomicBoolean pressure) {
        StorageEngine storage = MemTable.createForTest(new MutableClock(0),
                new MemoryManager(1 << 30, 0));
        return new CommandEngine(CommandRegistry.createDefault(), storage, null,
                pressure::get);
    }

    @Test
    void rejectsWritesWhenOverLimit() {
        AtomicBoolean pressure = new AtomicBoolean(true);
        CommandEngine engine = engineUnderPressure(pressure);

        RespValue result = run(engine, "set", "k", "v")[0];

        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message()).contains("OOM");
        assertThat(((RespError) result).message()).contains("maxmemory");
    }

    @Test
    void servesReadsWhileOverLimit() {
        AtomicBoolean pressure = new AtomicBoolean(true);
        CommandEngine engine = engineUnderPressure(pressure);

        // 读命令在压力下仍应正常执行（即使键不存在也只返回 nil）
        RespValue result = run(engine, "get", "missing")[0];

        assertThat(result).isNotInstanceOf(RespError.class);
    }

    @Test
    void writesPassWhenNoPressure() {
        AtomicBoolean pressure = new AtomicBoolean(false);
        CommandEngine engine = engineUnderPressure(pressure);

        assertThat(run(engine, "set", "k", "v")[0]).isNotInstanceOf(RespError.class);
        RespValue value = run(engine, "get", "k")[0];
        assertThat(value).isInstanceOf(RespBulkString.class);
        assertThat(new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8)).isEqualTo("v");
    }

    @Test
    void pressureIsEvaluatedPerCommand() {
        AtomicBoolean pressure = new AtomicBoolean(true);
        CommandEngine engine = engineUnderPressure(pressure);
        assertThat(run(engine, "set", "k", "v")[0]).isInstanceOf(RespError.class);

        pressure.set(false);
        assertThat(run(engine, "set", "k", "v")[0]).isNotInstanceOf(RespError.class);
    }

    @Test
    void engineWithoutProbeKeepsLegacyBehaviour() {
        StorageEngine storage = MemTable.createForTest(new MutableClock(0),
                new MemoryManager(1 << 30, 0));
        CommandEngine engine = new CommandEngine(CommandRegistry.createDefault(), storage);
        assertThat(run(engine, "set", "k", "v")[0]).isNotInstanceOf(RespError.class);
    }
}
