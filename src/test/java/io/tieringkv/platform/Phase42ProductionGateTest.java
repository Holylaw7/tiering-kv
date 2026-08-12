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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase 42 生产门禁（JVM 级）：leveled/悲观/async/coprocessor/PD。 */
class Phase42ProductionGateTest {

    @Test
    void leveledExecutionGate() {
        LeveledCompactionPlanner planner =
                new LeveledCompactionPlanner();
        LeveledCompactionExecutor executor =
                new LeveledCompactionExecutor();
        var plan = planner.planLevel(200, 100, 64, 0);
        var result = executor.execute(plan, List.of(
                new Entry("k1", new byte[1], false, 0),
                new Entry("k1", null, true, 0)), 0);
        assertThat(result.deletedEntries()).isEqualTo(1);
        assertThat(executor.summarize(List.of(
                new Entry("k1", new byte[1], false, 0),
                new Entry("k1", null, true, 0)), 0)).isEmpty();
    }

    @Test
    void pessimisticGate() {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        assertThat(txn.lock("k1", "t1", 100, 0)).isTrue();
        assertThat(txn.lock("k1", "t2", 100, 0)).isFalse();
        txn.write("k1", new byte[]{1});
        assertThat(txn.read("k1")).isEqualTo(new byte[]{1});
        txn.commit();
    }

    @Test
    void asyncCommitGate() {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        assertThat(coordinator.commit("t1", 1).onePhase()).isTrue();
        assertThat(coordinator.commit("t1", 3).onePhase())
                .isFalse();
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        service.advance(100);
        assertThat(service.advance(50)).isEqualTo(100);
    }

    @Test
    void coprocessorGate() {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "a", "d", 50, List.of());
        var result = executor.execute(request, List.of(
                new Row("a", 10), new Row("b", 60),
                new Row("c", 70)));
        assertThat(result).hasSize(2);
    }

    @Test
    void autonomousPdGate() {
        AutonomousPdScheduler scheduler = new AutonomousPdScheduler(
                1);
        assertThat(scheduler.execute(
                new Move("n1", "n2", 1)).executed()).isTrue();
        assertThat(scheduler.execute(
                new Move("n2", "n3", 1)).executed()).isFalse();
    }

    @Test
    void topologyDiscoveryGate() {
        TopologyDiscovery discovery = new TopologyDiscovery(100);
        discovery.heartbeat(new Heartbeat("n1", "r1", "az-1", 0),
                50);
        discovery.heartbeat(new Heartbeat("n2", "r1", "az-1", 0),
                500);
        assertThat(discovery.nodes().stream()
                .filter(n -> n.healthy()).count()).isEqualTo(1);
        assertThat(discovery.groupByRegion().get("r1"))
                .containsExactlyInAnyOrder("n1", "n2");
    }

    @ParameterizedTest(name = "regions {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedAsyncRegions(int regions) {
        AsyncCommitCoordinator coordinator =
                new AsyncCommitCoordinator();
        assertThat(coordinator.commit("t", regions).onePhase())
                .isEqualTo(regions == 1);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedPessimisticKeys(int count) {
        PessimisticTransaction txn = new PessimisticTransaction(500);
        txn.begin("t1");
        for (int i = 0; i < count; i++) {
            assertThat(txn.lock("k" + i, "t1", 100, 0)).isTrue();
        }
        assertThat(txn.lockedKeys()).hasSize(count);
    }

    @ParameterizedTest(name = "threshold {0}")
    @ValueSource(doubles = {0, 50, 100})
    void parameterizedCoprocessorThresholds(double threshold) {
        CoprocessorExecutor executor = new CoprocessorExecutor();
        CoprocessorRequest request = new CoprocessorRequest(
                Operator.FILTER, "a", "z", threshold, List.of());
        assertThat(executor.execute(request, List.of(
                new Row("a", 50), new Row("b", 100)))).isNotEmpty();
    }

    @ParameterizedTest(name = "limit {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedPdLimits(int limit) {
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

    @ParameterizedTest(name = "nodes {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedTopologyNodes(int count) {
        TopologyDiscovery discovery = new TopologyDiscovery(1000);
        for (int i = 0; i < count; i++) {
            discovery.heartbeat(new Heartbeat("n" + i,
                    "r" + (i % 2), "az-" + (i % 3), 0), 500);
        }
        assertThat(discovery.size()).isEqualTo(count);
    }

    @Test
    void resolvedTsMonotonicGate() {
        ResolvedTimestampService service =
                new ResolvedTimestampService();
        service.advance(5);
        service.advance(3);
        assertThat(service.resolvedTs()).isEqualTo(5);
        service.advance(9);
        assertThat(service.resolvedTs()).isEqualTo(9);
    }

    @Test
    void leveledNullInputsRejectedGate() {
        assertThatThrownBy(() -> new LeveledCompactionExecutor()
                .execute(null, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
