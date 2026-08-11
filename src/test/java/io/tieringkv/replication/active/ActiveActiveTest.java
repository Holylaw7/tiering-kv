package io.tieringkv.replication.active;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.ReplicaSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 全球 Active-Active（ADR-0135）：多地域写、环回抑制、冲突合并。 */
class ActiveActiveTest {

    @Test
    void writeBroadcastsToPeers() {
        RecordingSink peer = new RecordingSink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(peer), "r1", 2_000);
        assertThat(pipeline.write(bytes("k"), bytes("v")).join())
                .isTrue();
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("v"));
    }

    @Test
    void receiveSuppressesLoop() {
        RecordingSink peer = new RecordingSink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v")).join();
        pipeline.receive(bytes("k"), bytes("v"), "r1", 1);
        assertThat(pipeline.suppressedCount()).isEqualTo(1);
    }

    @Test
    void concurrentWritesConverge() {
        RecordingSink aPeer = new RecordingSink("r2");
        RecordingSink bPeer = new RecordingSink("r1");
        ActiveActivePipeline a = new ActiveActivePipeline(
                List.of(aPeer), "r1", 2_000);
        ActiveActivePipeline b = new ActiveActivePipeline(
                List.of(bPeer), "r2", 2_000);
        a.write(bytes("k"), bytes("va")).join();
        b.write(bytes("k"), bytes("vb")).join();
        a.receive(bytes("k"), bytes("vb"), "r2", 1);
        b.receive(bytes("k"), bytes("va"), "r1", 1);
        assertThat(a.get(bytes("k"))).isEqualTo(bytes("vb"));
        assertThat(b.get(bytes("k"))).isEqualTo(bytes("vb"));
    }

    @Test
    void conflictMetricsTracked() {
        RecordingSink peer = new RecordingSink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v1")).join();
        pipeline.receive(bytes("k"), bytes("v2"), "r2", 10);
        assertThat(pipeline.metrics().conflicts()).isEqualTo(1);
    }

    @Test
    void convergenceSamplesRecorded() {
        ConflictMetrics metrics = new ConflictMetrics();
        metrics.recordConvergence(10);
        metrics.recordConvergence(30);
        assertThat(metrics.avgConvergenceMillis()).isEqualTo(20);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 50, 200})
    void parameterizedWrites(int count) {
        RecordingSink peer = new RecordingSink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(peer), "r1", 5_000);
        for (int i = 0; i < count; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        assertThat(pipeline.get(bytes("k" + (count - 1))))
                .isEqualTo(bytes("v" + (count - 1)));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedConflictRounds(int rounds) {
        RecordingSink aPeer = new RecordingSink("r2");
        RecordingSink bPeer = new RecordingSink("r1");
        ActiveActivePipeline a = new ActiveActivePipeline(
                List.of(aPeer), "r1", 2_000);
        ActiveActivePipeline b = new ActiveActivePipeline(
                List.of(bPeer), "r2", 2_000);
        for (int i = 0; i < rounds; i++) {
            a.write(bytes("k" + i), bytes("a" + i)).join();
            b.write(bytes("k" + i), bytes("b" + i)).join();
            a.receive(bytes("k" + i), bytes("b" + i), "r2", i + 1);
            b.receive(bytes("k" + i), bytes("a" + i), "r1", i + 1);
        }
        assertThat(a.get(bytes("k" + (rounds - 1))))
                .isEqualTo(bytes("b" + (rounds - 1)));
        assertThat(b.get(bytes("k" + (rounds - 1))))
                .isEqualTo(bytes("b" + (rounds - 1)));
    }

    @Test
    void noPeersWriteSucceeds() {
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(), "r1", 1_000);
        assertThat(pipeline.write(bytes("k"), bytes("v")).join())
                .isTrue();
    }

    @Test
    void versionVectorTracks() {
        RecordingSink peer = new RecordingSink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v")).join();
        assertThat(pipeline.vector().version("r1")).isEqualTo(1);
    }

    @Test
    void staleRemoteIgnored() {
        RecordingSink peer = new RecordingSink("r2");
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(peer), "r1", 2_000);
        pipeline.write(bytes("k"), bytes("v2")).join();
        pipeline.receive(bytes("k"), bytes("v1"), "r2", 0);
        assertThat(pipeline.get(bytes("k"))).isEqualTo(bytes("v2"));
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
