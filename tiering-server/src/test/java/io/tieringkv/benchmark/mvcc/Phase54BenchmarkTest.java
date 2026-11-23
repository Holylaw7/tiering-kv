package io.tieringkv.benchmark.mvcc;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 54 基准（进程内口径）：事务/Stream 吞吐。 */
class Phase54BenchmarkTest {

    @ParameterizedTest(name = "watch-exec {0}")
    @ValueSource(ints = {1000, 5000})
    void watchExecThroughput(int txns) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < txns; i++) {
            ConnectionContext context = new ConnectionContext();
            ConnectionContext.attach(context);
            try {
                runner.exec("set", "k" + i, "v");
                runner.exec("watch", "k" + i);
                runner.exec("multi");
                runner.exec("get", "k" + i);
                runner.exec("exec");
            } finally {
                ConnectionContext.detach();
            }
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE54-BENCH WATCH-EXEC %d -> %d ops/s%n",
                txns, txns * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "xadd {0}")
    @ValueSource(ints = {1000, 5000})
    void xaddThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("xadd", "s", "*", "f", "v");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE54-BENCH XADD %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "xrange {0}")
    @ValueSource(ints = {1000, 5000})
    void xrangeThroughput(int ops) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        for (int i = 0; i < 100; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            runner.exec("xrange", "s", "-", "+");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE54-BENCH XRANGE %d -> %d ops/s%n",
                ops, ops * 1000L / elapsedMs);
    }
}
