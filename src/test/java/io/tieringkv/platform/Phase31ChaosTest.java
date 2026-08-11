package io.tieringkv.platform;

import io.tieringkv.replication.active.ActiveActivePipeline;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.sharding.auto.AutoReshardController;
import io.tieringkv.sharding.auto.LoadProbe;
import io.tieringkv.sharding.auto.ReshardPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 31 混沌：熔断、分区、冲突收敛。 */
class Phase31ChaosTest {

    @Test
    void autoReshardCircuitBreakerPreventsAmplification() {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 50, 2));
        for (int i = 0; i < 2; i++) {
            controller.onFailure();
        }
        assertThat(controller.tripped()).isTrue();
        assertThat(controller.decide(new LoadProbe(5000, 5, 100)))
                .isEqualTo(AutoReshardController.Decision.NOOP);
    }

    @ParameterizedTest(name = "failures {0}")
    @ValueSource(ints = {1, 3, 5})
    void circuitBreakerThresholds(int failures) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 50, 3));
        for (int i = 0; i < failures; i++) {
            controller.onFailure();
        }
        assertThat(controller.tripped()).isEqualTo(failures >= 3);
    }

    @Test
    void activeActivePartitionNoLoopStorm() {
        ReplicaSink aPeer = new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String replicaId() {
                return "r2";
            }
        };
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(aPeer), "r1", 2_000);
        for (int i = 0; i < 20; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        for (int i = 0; i < 20; i++) {
            pipeline.receive(bytes("k" + i), bytes("v" + i),
                    "r1", i + 1);
        }
        assertThat(pipeline.suppressedCount()).isEqualTo(20);
    }

    @Test
    void activeActiveConcurrentWritesConverge() {
        ReplicaSink aPeer = new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String replicaId() {
                return "r2";
            }
        };
        ReplicaSink bPeer = new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String replicaId() {
                return "r1";
            }
        };
        ActiveActivePipeline a = new ActiveActivePipeline(
                List.of(aPeer), "r1", 2_000);
        ActiveActivePipeline b = new ActiveActivePipeline(
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
    void conflictMetricsAfterChaos() {
        io.tieringkv.replication.active.ConflictMetrics metrics =
                new io.tieringkv.replication.active.ConflictMetrics();
        for (int i = 0; i < 100; i++) {
            metrics.recordConflict();
        }
        assertThat(metrics.conflicts()).isEqualTo(100);
        assertThat(metrics.lastConflictAt()).isGreaterThan(0);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
