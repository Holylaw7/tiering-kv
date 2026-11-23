package io.tieringkv.benchmark.command;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 命令路径 allocation 基线（ADR-0330，TD-020/021）：ThreadMXBean
 * 近似测量 PING 异步执行的总分配（含 key 字节缓存优化后）。
 */
@Tag("benchmark")
class CommandAllocationBenchmarkTest {

    @Test
    void asyncPingAllocationBaseline() throws Exception {
        if (!(ManagementFactory.getThreadMXBean()
                instanceof com.sun.management.ThreadMXBean bean)) {
            System.out.println("PHASE64-BENCH ALLOC unsupported");
            return;
        }
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefault(), MemTable.create());
        RespCommand ping = new RespCommand("PING", List.of());
        // 预热
        for (int i = 0; i < 1_000; i++) {
            engine.executeAsync(ping, (value, error) -> {
            });
        }
        int rounds = 100_000;
        long before = bean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < rounds; i++) {
            engine.executeAsync(ping, (value, error) -> {
            });
        }
        long after = bean.getCurrentThreadAllocatedBytes();
        double bytesPerRequest =
                (after - before) / (double) rounds;
        System.out.printf(Locale.ROOT,
                "PHASE64-BENCH ALLOC rounds=%d "
                        + "bytesPerAsyncRequest=%.1f%n",
                rounds, bytesPerRequest);
    }

    @Test
    void callbackPathCompletes() throws Exception {
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefault(), MemTable.create());
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        engine.executeAsync(new RespCommand("PING", List.of()),
                (value, error) -> {
                    if (value != null) {
                        result.set(value.toString());
                    }
                    done.countDown();
                });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).contains("PONG");
    }
}
