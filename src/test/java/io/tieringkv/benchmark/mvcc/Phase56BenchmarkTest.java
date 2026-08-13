package io.tieringkv.benchmark.mvcc;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.distributed.harness.VerificationHarness;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Phase 56 基准（进程内口径）：harness/消费组高级。 */
class Phase56BenchmarkTest {

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {100, 500})
    void harnessThroughput(int ops) throws Exception {
        long start = System.nanoTime();
        new VerificationHarness(4, ops, "k").run();
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE56-BENCH HARNESS %d -> %d ops/s%n",
                ops * 4, ops * 4 * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "xautoclaim {0}")
    @ValueSource(ints = {100, 500})
    void xautoclaimThroughput(int reads) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        for (int i = 1; i <= 10; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        runner.exec("xgroup", "create", "s", "g", "0");
        runner.exec("xreadgroup", "group", "g", "c",
                "streams", "s", ">");
        long start = System.nanoTime();
        for (int i = 0; i < reads; i++) {
            runner.exec("xautoclaim", "s", "g", "c", "0",
                    "1-1");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE56-BENCH XAUTOCLAIM %d -> %d ops/s%n",
                reads, reads * 1000L / elapsedMs);
    }
}
