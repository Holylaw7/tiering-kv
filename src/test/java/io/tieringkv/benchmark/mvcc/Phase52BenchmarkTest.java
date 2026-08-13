package io.tieringkv.benchmark.mvcc;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 52 基准（进程内口径）：数据结构命令吞吐。 */
class Phase52BenchmarkTest {

    @ParameterizedTest(name = "hset {0}")
    @ValueSource(ints = {1000, 10_000})
    void hsetThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("hset", "h", "f" + (i % 100), "v");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE52-BENCH HSET %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "rpush {0}")
    @ValueSource(ints = {1000, 10_000})
    void rpushThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("rpush", "l", "v");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE52-BENCH RPUSH %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "sadd {0}")
    @ValueSource(ints = {1000, 10_000})
    void saddThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("sadd", "s", "m" + (i % 100));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE52-BENCH SADD %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "zadd {0}")
    @ValueSource(ints = {1000, 10_000})
    void zaddThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("zadd", "z", Integer.toString(i % 100),
                    "m" + (i % 100));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE52-BENCH ZADD %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }
}
