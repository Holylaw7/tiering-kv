package io.tieringkv.benchmark.mvcc;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 53 基准（进程内口径）：事务/接线/高级命令吞吐。 */
class Phase53BenchmarkTest {

    @ParameterizedTest(name = "multi-exec {0}")
    @ValueSource(ints = {1000, 5000})
    void multiExecThroughput(int txns) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < txns; i++) {
            ConnectionContext context = new ConnectionContext();
            ConnectionContext.attach(context);
            try {
                runner.exec("multi");
                runner.exec("set", "k" + i, "v");
                runner.exec("exec");
            } finally {
                ConnectionContext.detach();
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE53-BENCH MULTI-EXEC %d -> %d ops/s%n",
                txns, txns * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "hscan {0}")
    @ValueSource(ints = {1000, 5000})
    void hscanThroughput(int scans) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        for (int i = 0; i < 50; i++) {
            runner.exec("hset", "h", "f" + i, "v");
        }
        long start = System.nanoTime();
        for (int i = 0; i < scans; i++) {
            runner.exec("hscan", "h", "0");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE53-BENCH HSCAN %d -> %d ops/s%n",
                scans, scans * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "lmove {0}")
    @ValueSource(ints = {1000, 5000})
    void lmoveThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "src", "a", "b", "c");
        runner.exec("rpush", "dst", "x");
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("lmove", "src", "dst", "left", "right");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE53-BENCH LMOVE %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "zrangebylex {0}")
    @ValueSource(ints = {1000, 5000})
    void zrangebylexThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        for (int i = 0; i < 100; i++) {
            runner.exec("zadd", "z", "1", "m" + i);
        }
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("zrangebylex", "z", "-", "+");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE53-BENCH ZRANGEBYLEX %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }
}
