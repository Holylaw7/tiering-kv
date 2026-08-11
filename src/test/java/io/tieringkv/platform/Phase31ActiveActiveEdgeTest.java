package io.tieringkv.platform;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.replication.active.ActiveActivePipeline;
import io.tieringkv.replication.active.ConflictMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 31 Active-Active 边缘：写入/冲突/收敛参数矩阵。 */
class Phase31ActiveActiveEdgeTest {

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void writeVolumeMatrix(int count) {
        ReplicaSink sink = new ReplicaSink() {
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
                List.of(sink), "r1", 5_000);
        for (int i = 0; i < count; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        assertThat(pipeline.get(bytes("k" + (count - 1))))
                .isEqualTo(bytes("v" + (count - 1)));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void conflictRoundsMatrix(int rounds) {
        ReplicaSink aPeer = sink("r2");
        ReplicaSink bPeer = sink("r1");
        ActiveActivePipeline a = new ActiveActivePipeline(
                List.of(aPeer), "r1", 2_000);
        ActiveActivePipeline b = new ActiveActivePipeline(
                List.of(bPeer), "r2", 2_000);
        for (int i = 0; i < rounds; i++) {
            a.write(bytes("k" + i), bytes("a")).join();
            b.write(bytes("k" + i), bytes("b")).join();
            a.receive(bytes("k" + i), bytes("b"), "r2", i + 1);
            b.receive(bytes("k" + i), bytes("a"), "r1", i + 1);
        }
        assertThat(a.get(bytes("k" + (rounds - 1))))
                .isEqualTo(bytes("b"));
    }

    @ParameterizedTest(name = "conflicts {0}")
    @ValueSource(ints = {1, 10, 100, 1000, 10000, 100000})
    void conflictMetricsVolume(int count) {
        ConflictMetrics metrics = new ConflictMetrics();
        for (int i = 0; i < count; i++) {
            metrics.recordConflict();
        }
        assertThat(metrics.conflicts()).isEqualTo(count);
    }

    @ParameterizedTest(name = "samples {0}")
    @ValueSource(ints = {1, 10, 100, 1000, 10000, 100000})
    void convergenceSamplesVolume(int count) {
        ConflictMetrics metrics = new ConflictMetrics();
        for (int i = 0; i < count; i++) {
            metrics.recordConvergence(i % 100);
        }
        assertThat(metrics.avgConvergenceMillis())
                .isBetween(0.0, 100.0);
    }

    @Test
    void suppressedLoopsZero() {
        ReplicaSink sink = sink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(sink), "r1", 2_000);
        assertThat(pipeline.suppressedCount()).isZero();
    }

    private static ReplicaSink sink(String id) {
        return new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String replicaId() {
                return id;
            }
        };
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
