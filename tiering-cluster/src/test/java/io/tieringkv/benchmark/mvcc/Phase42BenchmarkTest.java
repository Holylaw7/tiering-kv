package io.tieringkv.benchmark.mvcc;

import io.tieringkv.cluster.scheduler.AutonomousPdScheduler;
import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.cluster.topology.TopologyDiscovery.Heartbeat;
import io.tieringkv.sql.coprocessor.CoprocessorExecutor;
import io.tieringkv.sql.coprocessor.CoprocessorRequest;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Operator;
import io.tieringkv.sql.coprocessor.CoprocessorRequest.Row;
import io.tieringkv.storage.compaction.LeveledCompactionExecutor;
import io.tieringkv.storage.compaction.LeveledCompactionExecutor.Entry;
import io.tieringkv.storage.compaction.LeveledCompactionPlanner;
import io.tieringkv.transaction.async.AsyncCommitCoordinator;
import io.tieringkv.transaction.async.ResolvedTimestampService;
import io.tieringkv.transaction.pessimistic.PessimisticTransaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

/** Phase 42 基准（进程内口径，如实记录；跨地域 Runner 待执行）。 */
class Phase42BenchmarkTest {

    @ParameterizedTest(name = "commits {0}")
    @ValueSource(ints = {1000, 10000})
    void asyncCommitThroughput(int commits) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        long start = System.nanoTime();
        for (int i = 0; i < commits; i++) {
            coordinator.commit("t" + i, 1);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE42-BENCH ASYNC-COMMIT %d -> %d ops/s%n",
                commits, commits * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "locks {0}")
    @ValueSource(ints = {1000, 10000})
    void pessimisticLockThroughput(int locks) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        long start = System.nanoTime();
        for (int i = 0; i < locks; i++) {
            txn.lock("k" + i, "t1", 100, 0);
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE42-BENCH PESSIMISTIC %d -> %d ops/s%n",
                locks, locks * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1000, 10000})
    void coprocessorThroughput(int rows) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, i));
        }
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "k0", "zz", 500, List.of());
        long start = System.nanoTime();
        executor.execute(request, data);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE42-BENCH COPROC %d -> %d rows/s%n",
                rows, rows * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "entries {0}")
    @ValueSource(ints = {1000, 10000})
    void leveledExecuteThroughput(int entries) {
        LeveledCompactionExecutor executor =
                new LeveledCompactionExecutor();
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        var plan = planner.planLevel(10_000, 1000, 128, 0);
        List<Entry> data = new ArrayList<>();
        for (int i = 0; i < entries; i++) {
            data.add(new Entry("k" + i, new byte[8], false, 0));
        }
        long start = System.nanoTime();
        executor.execute(plan, data, 0);
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE42-BENCH LEVELED-EXEC %d -> %d/s%n",
                entries, entries * 1_000L / elapsedMs);
    }

    @ParameterizedTest(name = "moves {0}")
    @ValueSource(ints = {1000, 10000})
    void pdAutonomyThroughput(int moves) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                10_000);
        long start = System.nanoTime();
        for (int i = 0; i < moves; i++) {
            scheduler.execute(new Move("n" + i, "n" + (i + 1), 1));
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE42-BENCH PD-AUTO %d -> %d ops/s%n",
                moves, moves * 1_000L / elapsedMs);
    }

    @Test
    void topologyDiscoveryLatency() {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            discovery.heartbeat(new Heartbeat("n" + (i % 100),
                    "r" + (i % 3), "az-" + (i % 2), 0), 500);
            discovery.groupByRegion();
        }
        long elapsedMs = Math.max(1,
                (System.nanoTime() - start) / 1_000_000);
        System.out.printf("PHASE42-BENCH TOPOLOGY %d ms%n",
                elapsedMs);
    }

    @Test
    void resolvedTsIntegration() {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        for (int i = 0; i < 10_000; i++) {
            service.advance(i);
        }
        org.junit.jupiter.api.Assertions.assertEquals(9999,
                service.resolvedTs());
    }
}
