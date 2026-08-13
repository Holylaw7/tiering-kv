package io.tieringkv.benchmark.mvcc;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 51 基准（进程内口径）：字符串/TTL/多键/SCAN 命令吞吐。 */
class Phase51BenchmarkTest {

    @ParameterizedTest(name = "incr {0}")
    @ValueSource(ints = {1000, 10_000})
    void incrThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("incr", "bench-key");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE51-BENCH INCR %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "mset {0}")
    @ValueSource(ints = {1000, 10_000})
    void msetThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("mset", "a" + i, "v", "b" + i, "v");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE51-BENCH MSET %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "scan {0}")
    @ValueSource(ints = {1000, 10_000})
    void scanThroughput(int keys) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        for (int i = 0; i < keys; i++) {
            runner.exec("set", "k" + i, "v");
        }
        long start = System.nanoTime();
        long cursor = 0;
        do {
            var result = (io.tieringkv.protocol.RespArray)
                    runner.exec("scan", cursor, "count", "100");
            cursor = Long.parseLong(new String(
                    ((io.tieringkv.protocol.RespBulkString)
                            result.values().get(0)).bytes()));
        } while (cursor != 0);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE51-BENCH SCAN %d -> %d keys/s%n",
                keys, keys * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(ints = {1000, 10_000})
    void ttlThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "k", "v");
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("ttl", "k");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE51-BENCH TTL %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @Test
    void registrySizeBaseline() {
        int size = io.tieringkv.command.CommandRegistry
                .createDefault().size();
        System.out.printf("PHASE51-BENCH COMMAND-COUNT %d%n",
                size);
    }
}
