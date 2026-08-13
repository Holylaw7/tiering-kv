package io.tieringkv.benchmark.mvcc;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.distributed.LinearizabilityChecker;
import io.tieringkv.distributed.LinearizabilityChecker.Operation;
import io.tieringkv.distributed.LinearizabilityChecker.OpType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

/** Phase 55 基准（进程内口径）：线性化/消费组。 */
class Phase55BenchmarkTest {

    @ParameterizedTest(name = "history {0}")
    @ValueSource(ints = {1000, 5000})
    void linearizabilityCheckThroughput(int checks) {
        List<Operation> history = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            history.add(new Operation(i * 2L, i * 2L + 1,
                    OpType.PUT, "k", "v" + i, null));
        }
        history.add(new Operation(100L, 101L, OpType.GET, "k",
                null, "v5"));
        long start = System.nanoTime();
        for (int i = 0; i < checks; i++) {
            LinearizabilityChecker.isLinearizable(history);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE55-BENCH LINEARIZABILITY %d -> %d/s%n",
                checks, checks * 1000L / elapsedMs);
    }

    @ParameterizedTest(name = "xreadgroup {0}")
    @ValueSource(ints = {1000, 5000})
    void consumerGroupThroughput(int reads) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        for (int i = 1; i <= 20; i++) {
            runner.exec("xadd", "s", i + "-1", "f", "v");
        }
        runner.exec("xgroup", "create", "s", "g", "0");
        long start = System.nanoTime();
        for (int i = 0; i < reads; i++) {
            runner.exec("xreadgroup", "group", "g", "c1",
                    "streams", "s", ">");
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf(
                "PHASE55-BENCH XREADGROUP %d -> %d ops/s%n",
                reads, reads * 1000L / elapsedMs);
    }
}
