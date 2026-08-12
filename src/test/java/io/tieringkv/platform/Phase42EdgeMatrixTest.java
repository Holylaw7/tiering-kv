package io.tieringkv.platform;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 42 参数化边缘矩阵：leveled/悲观/async/coprocessor/PD/拓扑。 */
class Phase42EdgeMatrixTest {

    private final LeveledCompactionPlanner planner =
            new LeveledCompactionPlanner();
    private final LeveledCompactionExecutor executor =
            new LeveledCompactionExecutor();

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void leveledEntryCounts(int count) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
        }
        assertThat(executor.summarize(entries, 0)).hasSize(count);
    }

    @ParameterizedTest(name = "expired {0}")
    @ValueSource(ints = {0, 5, 10, 25, 50})
    void leveledExpiredCounts(int expired) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(entry("k" + i, false,
                    i < expired ? 10 : 0));
        }
        var result = executor.execute(plan, entries, 100);
        assertThat(result.deletedEntries()).isEqualTo(expired);
    }

    @ParameterizedTest(name = "tombstones {0}")
    @ValueSource(ints = {0, 5, 10, 25, 50})
    void leveledTombstoneCounts(int tombstones) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(entry("k" + i, i < tombstones, 0));
        }
        var result = executor.execute(plan, entries, 0);
        assertThat(result.deletedEntries()).isEqualTo(tombstones);
    }

    @ParameterizedTest(name = "level {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void leveledSourceLevels(int level) {
        var plan = planner.planLevel(1000, 100, 64, level);
        assertThat(plan.targetLevel()).isEqualTo(level + 1);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {1, 10, 100, 1000, 10_000})
    void leveledFileSequence(int count) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
        }
        long before = executor.fileSequence();
        executor.execute(plan, entries, 0);
        assertThat(executor.fileSequence()).isGreaterThan(before);
    }

    @ParameterizedTest(name = "timeout {0}")
    @ValueSource(longs = {1, 10, 100, 500, 1000})
    void pessimisticTimeouts(long timeout) {
        PessimisticTransaction txn = new PessimisticTransaction(
                timeout);
        txn.begin("t1");
        assertThat(txn.lock("k1", "t1", timeout, 0)).isTrue();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pessimisticKeyCounts(int count) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            assertThat(txn.lock("k" + i, "t1", 100, 0)).isTrue();
        }
        assertThat(txn.lockedKeys()).hasSize(count);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void pessimisticRounds(int rounds) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < rounds; i++) {
            txn.lock("k" + i, "t1", 100, 0);
        }
        txn.commit();
        assertThat(txn.isOpen()).isFalse();
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(ints = {1, 10, 100, 1000, 10_000})
    void pessimisticValues(int size) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        txn.write("k1", new byte[size]);
        assertThat(txn.read("k1")).hasSize(size);
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void asyncRegionCounts(int regions) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        var result = coordinator.commit("t", regions);
        assertThat(result.onePhase()).isEqualTo(regions == 1);
    }

    @ParameterizedTest(name = "ts {0}")
    @ValueSource(longs = {0, 10, 100, 1000, 10_000})
    void resolvedTsValues(long ts) {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        assertThat(service.advance(ts)).isEqualTo(ts);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void resolvedTsRounds(int rounds) {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        for (int i = 0; i < rounds; i++) {
            service.advance(i);
        }
        assertThat(service.resolvedTs()).isEqualTo(rounds - 1);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(doubles = {0, 25, 50, 75, 100})
    void coprocessorThresholds(double threshold) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        var request = new CoprocessorRequest(Operator.FILTER,
                "a", "z", threshold, List.of());
        var result = executor.execute(request, rows());
        assertThat(result).extracting(Row::value)
                .allMatch(value -> value >= threshold);
    }

    @ParameterizedTest(name = "range {0}")
    @ValueSource(strings = {"a", "b", "c", "d", "e"})
    void coprocessorRanges(String start) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        var request = new CoprocessorRequest(Operator.AGGREGATE,
                start, "z", 0, List.of());
        assertThat(executor.execute(request, rows())).hasSize(1);
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void coprocessorRowCounts(int count) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            data.add(new Row("k" + i, i));
        }
        var request = new CoprocessorRequest(Operator.AGGREGATE,
                "k0", "zz", 0, List.of());
        var result = executor.execute(request, data);
        assertThat(result.get(0).value())
                .isEqualTo(count * (count - 1) / 2.0);
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdLimitCounts(int limit) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                limit);
        int executed = 0;
        for (int i = 0; i < limit * 2; i++) {
            if (scheduler.execute(new Move("n" + i, "n" + (i + 1),
                    1)).executed()) {
                executed++;
            }
        }
        assertThat(executed).isEqualTo(limit);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void pdRounds(int rounds) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1000);
        for (int i = 0; i < rounds; i++) {
            scheduler.execute(new Move("n" + i, "n" + (i + 1), 1));
        }
        assertThat(scheduler.executedMoves()).hasSize(rounds);
    }

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyNodeCounts(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 3), "az-" + (i % 2), 0), 500);
        }
        assertThat(discovery.size()).isEqualTo(count);
    }

    @ParameterizedTest(name = "timeout {0}")
    @ValueSource(longs = {1, 10, 100, 500, 1000})
    void topologyTimeouts(long timeout) {
        TopologyDiscovery discovery = new TopologyDiscovery(timeout);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 0),
                timeout + 1);
        assertThat(discovery.nodes().get(0).healthy()).isFalse();
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50, 100, 200})
    void topologyRounds(int rounds) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < rounds; i++) {
            discovery.heartbeat(new Heartbeat("n" + (i % 10),
                    "r" + (i % 3), "az-" + (i % 2), 0), 500);
        }
        assertThat(discovery.size()).isEqualTo(Math.min(10, rounds));
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void leveledExpiredMix(int total, int expired) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            entries.add(entry("k" + i, false,
                    i < expired ? 10 : 0));
        }
        var result = executor.execute(plan, entries, 100);
        assertThat(result.deletedEntries()).isEqualTo(expired);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,1", "10,2", "20,4", "50,10", "100,20"})
    void pessimisticConflictMix(int total, int conflicts) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < total; i++) {
            txn.lock("k" + i, "t1", 100, 0);
        }
        int rejected = 0;
        for (int i = 0; i < conflicts; i++) {
            if (!txn.lock("k" + i, "t2", 100, 0)) {
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(conflicts);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void coprocessorRowMix(int rows, int sum) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, 1));
        }
        var request = new CoprocessorRequest(Operator.AGGREGATE,
                "k0", "zz", 0, List.of());
        assertThat(executor.execute(request, data).get(0).value())
                .isEqualTo(rows);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void topologyRegionMix(int nodes, int regions) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < nodes; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % regions), "az-1", 0), 500);
        }
        assertThat(discovery.groupByRegion()).hasSize(regions);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdNewRoundVolumes(int count) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1);
        for (int i = 0; i < count; i++) {
            scheduler.execute(new Move("n" + i, "n" + (i + 1), 1));
            scheduler.newRound();
        }
        assertThat(scheduler.executedMoves()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void resolvedTsConcurrentVolumes(int count) throws Exception {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    service.advance(i);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(service.resolvedTs()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledSummarizeVolumes(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
        }
        assertThat(executor.summarize(entries, 0)).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pessimisticWriteVolumes(int count) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            txn.write("k" + i, new byte[]{1});
        }
        txn.commit();
        assertThat(txn.isOpen()).isFalse();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void asyncCommitVolumes(int count) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        for (int i = 0; i < count; i++) {
            assertThat(coordinator.commit("t" + i, 1).succeeded())
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void coprocessorFilterVolumes(int count) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        var request = new CoprocessorRequest(Operator.FILTER,
                "a", "z", 0, List.of());
        for (int i = 0; i < count; i++) {
            assertThat(executor.execute(request, rows()))
                    .isNotEmpty();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdExecutedVolumes(int count) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                count);
        for (int i = 0; i < count; i++) {
            scheduler.execute(new Move("n" + i, "n" + (i + 1), 1));
        }
        assertThat(scheduler.executedMoves()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyGroupVolumes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 2), "az-" + (i % 2), 0), 500);
        }
        assertThat(discovery.groupByAz())
                .hasSize(Math.min(2, count));
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,3", "10,5", "20,10", "50,25", "100,50"})
    void leveledMixedEntries(int total, int tombstones) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            entries.add(entry("k" + i, i < tombstones, 0));
        }
        var result = executor.execute(plan, entries, 0);
        assertThat(result.deletedEntries()).isEqualTo(tombstones);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,1", "10,2", "20,4", "50,10", "100,20"})
    void pessimisticLockMix(int keys, int conflicts) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < keys; i++) {
            txn.lock("k" + i, "t1", 100, 0);
        }
        for (int i = 0; i < conflicts; i++) {
            assertThat(txn.lock("k" + i, "t2", 100, 0)).isFalse();
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void asyncRegionMix(int txns, int regions) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        for (int i = 0; i < txns; i++) {
            assertThat(coordinator.commit("t" + i, regions)
                    .succeeded()).isTrue();
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void coprocessorAggregateMix(int rows, int expected) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, 2));
        }
        var request = new CoprocessorRequest(Operator.AGGREGATE,
                "k0", "zz", 0, List.of());
        assertThat(executor.execute(request, data).get(0).value())
                .isEqualTo(rows * 2);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void pdCircuitMix(int rounds, int openAt) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1000);
        for (int i = 0; i < rounds; i++) {
            if (i == openAt) {
                scheduler.openCircuit("x");
            }
            scheduler.execute(new Move("n" + i, "n" + (i + 1), 1));
        }
        assertThat(scheduler.executedMoves()).hasSize(openAt);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void topologyRemoveMix(int nodes, int removed) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < nodes; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 2), "az-1", 0), 500);
        }
        for (int i = 0; i < removed; i++) {
            discovery.remove("n" + i);
        }
        assertThat(discovery.size()).isEqualTo(nodes - removed);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledOutputVolumes(int count) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
        }
        assertThat(executor.execute(plan, entries, 0)
                .outputFiles()).isPositive();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pessimisticReadVolumes(int count) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            txn.write("k" + i, new byte[]{1});
            assertThat(txn.read("k" + i)).isEqualTo(new byte[]{1});
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void coprocessorProjectVolumes(int count) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        var request = new CoprocessorRequest(Operator.PROJECT,
                "a", "z", 2, List.of());
        for (int i = 0; i < count; i++) {
            assertThat(executor.execute(request, rows())).hasSize(4);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyHeartbeatVolumes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r1", "az-1", 0), 500);
        }
        assertThat(discovery.groupByRegion().get("r1"))
                .hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void resolvedTsAdvanceVolumes(int count) {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        for (int i = 0; i < count; i++) {
            service.advance(i * 10);
        }
        assertThat(service.resolvedTs())
                .isEqualTo((count - 1) * 10L);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdNewRoundResetVolumes(int count) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1);
        for (int i = 0; i < count; i++) {
            scheduler.execute(new Move("n" + i, "n" + (i + 1), 1));
            scheduler.newRound();
        }
        assertThat(scheduler.movesThisRound()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledNoCompactVolumes(int count) {
        var plan = planner.planLevel(50, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
        }
        assertThat(executor.execute(plan, entries, 0)
                .outputFiles()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledLiveSummaries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
            entries.add(entry("k" + i, true, 0));
        }
        assertThat(executor.summarize(entries, 0)).isEmpty();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pessimisticRollbackVolumes(int count) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            txn.lock("k" + i, "t1", 100, 0);
        }
        txn.rollback();
        assertThat(txn.isOpen()).isFalse();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void asyncTwoPhaseVolumes(int count) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        for (int i = 0; i < count; i++) {
            assertThat(coordinator.commitTwoPhase("t" + i, 3)
                    .succeeded()).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void coprocessorSortedVolumes(int count) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = count - 1; i >= 0; i--) {
            data.add(new Row("k" + i, i));
        }
        assertThat(executor.sorted(data)).extracting(Row::key)
                .isSorted();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdCircuitResetVolumes(int count) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                100);
        for (int i = 0; i < count; i++) {
            scheduler.openCircuit("x");
            scheduler.resetCircuit();
            assertThat(scheduler.execute(new Move("n" + i,
                    "n" + (i + 1), 1)).executed()).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyHealthyVolumes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r1", "az-1", 0), 500);
        }
        assertThat(discovery.nodes()).allMatch(
                io.tieringkv.cluster.topology.TopologyDiscovery
                        .NodeInfo::healthy);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void resolvedTsStaleVolumes(int count) {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        service.advance(100);
        for (int i = 0; i < count; i++) {
            assertThat(service.advance(i)).isEqualTo(100);
        }
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void leveledTtlMix(int total, int ttl) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            entries.add(entry("k" + i, false, i < ttl ? 10 : 0));
        }
        assertThat(executor.execute(plan, entries, 100)
                .deletedEntries()).isEqualTo(ttl);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void pessimisticTimeoutMix(int keys, int expired) {
        PessimisticTransaction txn = new PessimisticTransaction(100);
        txn.begin("t1");
        int rejected = 0;
        for (int i = 0; i < keys; i++) {
            try {
                txn.lock("k" + i, "t1",
                        i < expired ? 200 : 100, 0);
            } catch (IllegalStateException e) {
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(expired);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,10", "10,20", "20,50", "50,100", "100,200"})
    void coprocessorProjectMix(int rows, int factor) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        List<Row> data = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            data.add(new Row("k" + i, 1));
        }
        var request = new CoprocessorRequest(Operator.PROJECT,
                "k0", "zz", factor, List.of());
        assertThat(executor.execute(request, data)).hasSize(rows);
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void pdRoundLimitMix(int limit, int rounds) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                limit);
        int executed = 0;
        for (int i = 0; i < rounds; i++) {
            if (scheduler.execute(new Move("n" + i, "n" + (i + 1),
                    1)).executed()) {
                executed++;
            }
        }
        assertThat(executed).isEqualTo(Math.min(limit, rounds));
    }

    @ParameterizedTest(name = "mix {0}")
    @CsvSource({"5,2", "10,4", "20,8", "50,20", "100,40"})
    void topologyUnhealthyMix(int nodes, int stale) {
        TopologyDiscovery discovery = new TopologyDiscovery(100);
        for (int i = 0; i < nodes; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r1", "az-1", 0),
                    i < stale ? 500 : 50);
        }
        assertThat(discovery.nodes().stream()
                .filter(n -> !n.healthy()).count())
                .isEqualTo(stale);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledConcurrentVolumes(int count) throws Exception {
        LeveledCompactionExecutor local =
                new LeveledCompactionExecutor();
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 0));
        }
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    local.execute(plan, entries, 0);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(local.fileSequence()).isGreaterThan(0);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pessimisticConcurrentVolumes(int count) throws Exception {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    txn.lock("k" + i, "t1", 100, 0);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(txn.lockedKeys()).hasSize(count);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void coprocessorConcurrentVolumes(int count) throws Exception {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        var request = new CoprocessorRequest(Operator.FILTER,
                "a", "z", 0, List.of());
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    assertThat(executor.execute(request, rows()))
                            .isNotEmpty();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdConcurrentVolumes(int count) throws Exception {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1000);
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    scheduler.execute(new Move("n" + i,
                            "n" + (i + 1), 1));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(scheduler.executedMoves())
                .hasSize(count * 4);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledMixedTombstoneSummaries(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, i % 2 == 0, 0));
        }
        assertThat(executor.summarize(entries, 0))
                .hasSize(count / 2);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void asyncOnePhaseVolumes(int count) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        for (int i = 0; i < count; i++) {
            assertThat(coordinator.commitOnePhase("t" + i, 1)
                    .onePhase()).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void coprocessorFilterThresholdVolumes(int count) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        for (int i = 0; i < count; i++) {
            var request = new CoprocessorRequest(Operator.FILTER,
                    "a", "z", i, List.of());
            assertThat(executor.execute(request, rows()))
                    .isNotNull();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyRegionGroupVolumes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 3), "az-1", 0), 500);
        }
        assertThat(discovery.groupByRegion())
                .hasSize(Math.min(3, count));
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void resolvedTsFinalVolumes(int count) {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        for (int i = 0; i < count; i++) {
            service.advance(i);
        }
        assertThat(service.resolvedTs()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledLiveTtlVolumes(int count) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false,
                    i % 2 == 0 ? 10 : 0));
        }
        assertThat(executor.summarize(entries, 100))
                .hasSize(count / 2);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pessimisticReentrantVolumes(int count) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            assertThat(txn.lock("k", "t1", 100, i)).isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void asyncTwoPhaseOneVolumes(int count) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        for (int i = 0; i < count; i++) {
            assertThat(coordinator.commit("t" + i, 2).succeeded())
                    .isTrue();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void coprocessorAggregateThresholdVolumes(int count) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        for (int i = 0; i < count; i++) {
            var request = new CoprocessorRequest(Operator.AGGREGATE,
                    "a", "z", i, List.of());
            assertThat(executor.execute(request, rows()))
                    .hasSize(1);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void pdCircuitToggleVolumes(int count) {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                100);
        for (int i = 0; i < count; i++) {
            scheduler.openCircuit("x");
            scheduler.resetCircuit();
            assertThat(scheduler.circuitOpen()).isFalse();
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void topologyRemoveAllVolumes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r1", "az-1", 0), 500);
        }
        for (int i = 0; i < count; i++) {
            discovery.remove("n" + i);
        }
        assertThat(discovery.size()).isZero();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void resolvedTsSameVolumes(int count) {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        service.advance(50);
        for (int i = 0; i < count; i++) {
            assertThat(service.advance(50)).isEqualTo(50);
        }
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50})
    void leveledExpiredAllVolumes(int count) {
        var plan = planner.planLevel(1000, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, false, 10));
        }
        assertThat(executor.execute(plan, entries, 100)
                .deletedEntries()).isEqualTo(count);
    }

    private static List<Row> rows() {
        return List.of(
                new Row("a", 10),
                new Row("b", 50),
                new Row("c", 70),
                new Row("d", 100));
    }

    private static Entry entry(String key, boolean deleted,
                               long expireAt) {
        return new Entry(key, new byte[1], deleted, expireAt);
    }
}
