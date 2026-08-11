package io.tieringkv.monitor;

import io.tieringkv.dr.DrDrillRunner;
import io.tieringkv.dr.DrRole;
import io.tieringkv.dr.DrSwitchPlanner;
import io.tieringkv.dr.DrTopology;
import io.tieringkv.replication.BidirectionalPipeline;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.replication.ReplicationMode;
import io.tieringkv.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 混沌（Goal 8）：DR 切换、双向分区、指标采样。 */
class Phase28ChaosTest {

    @Test
    void primaryFailureFailoverDrill() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.SYNC));
        var plan = new DrSwitchPlanner().failover(topology, "a");
        var result = new DrDrillRunner().run(plan, () -> true, 120);
        assertThat(result.success()).isTrue();
        assertThat(result.rtoMillis()).isGreaterThanOrEqualTo(120);
        assertThat(result.rpoMillis()).isZero();
    }

    @Test
    void plannedSwitchDrill() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY,
                        "c", DrRole.OBSERVER),
                Map.of("a", ReplicationMode.SYNC,
                        "b", ReplicationMode.SYNC));
        var plan = new DrSwitchPlanner().plannedSwitch(
                topology, "a", "b");
        var result = new DrDrillRunner().run(plan, () -> true, 80);
        assertThat(result.success()).isTrue();
        assertThat(result.rpoMillis()).isZero();
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(ints = {10, 100, 500})
    void parameterizedDrillRto(int delayMillis) {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.SYNC));
        var plan = new DrSwitchPlanner().failover(topology, "a");
        var result = new DrDrillRunner().run(plan, () -> true,
                delayMillis);
        assertThat(result.rtoMillis()).isGreaterThanOrEqualTo(delayMillis);
    }

    @Test
    void bidirectionalPartitionNoLoopStorm() {
        RecordingSink aPeer = new RecordingSink("r2");
        RecordingSink bPeer = new RecordingSink("r1");
        BidirectionalPipeline a = new BidirectionalPipeline(
                List.of(aPeer), "r1", 2_000);
        BidirectionalPipeline b = new BidirectionalPipeline(
                List.of(bPeer), "r2", 2_000);
        for (int i = 0; i < 20; i++) {
            a.write(bytes("k" + i), bytes("va" + i)).join();
        }
        // 远端回灌全部已见版本 → 环回抑制，无重复应用
        for (int i = 0; i < 20; i++) {
            a.receive(bytes("k" + i), bytes("va" + i), "r1", i + 1);
        }
        assertThat(a.suppressedCount()).isEqualTo(20);
    }

    @Test
    void bidirectionalConcurrentWritesConverge() {
        RecordingSink aPeer = new RecordingSink("r2");
        RecordingSink bPeer = new RecordingSink("r1");
        BidirectionalPipeline a = new BidirectionalPipeline(
                List.of(aPeer), "r1", 2_000);
        BidirectionalPipeline b = new BidirectionalPipeline(
                List.of(bPeer), "r2", 2_000);
        for (int i = 0; i < 10; i++) {
            a.write(bytes("k" + i), bytes("a")).join();
            b.write(bytes("k" + i), bytes("b")).join();
            a.receive(bytes("k" + i), bytes("b"), "r2", i + 1);
            b.receive(bytes("k" + i), bytes("a"), "r1", i + 1);
        }
        assertThat(a.get(bytes("k9"))).isEqualTo(bytes("b"));
        assertThat(b.get(bytes("k9"))).isEqualTo(bytes("b"));
    }

    @Test
    void metricsTrackReplicationAndDr() {
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.increment("replication_lag_events");
        metrics.gauge("dr_rpo", 0);
        metrics.gauge("crdt_conflicts", 3);
        assertThat(metrics.counter("replication_lag_events"))
                .isEqualTo(1);
        assertThat(metrics.gauge("dr_rpo")).isZero();
        assertThat(metrics.gauge("crdt_conflicts")).isEqualTo(3);
        assertThat(metrics.snapshot())
                .containsKeys("replication_lag_events", "dr_rpo",
                        "crdt_conflicts");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 50, 200})
    void parameterizedMetricsCounters(int count) {
        Phase28Metrics metrics = new Phase28Metrics();
        for (int i = 0; i < count; i++) {
            metrics.increment("sql_query");
        }
        assertThat(metrics.counter("sql_query")).isEqualTo(count);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingSink implements ReplicaSink {
        private final String id;

        private RecordingSink(String id) {
            this.id = id;
        }

        @Override
        public CompletableFuture<Void> apply(ChangeEvent event) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String replicaId() {
            return id;
        }
    }
}
