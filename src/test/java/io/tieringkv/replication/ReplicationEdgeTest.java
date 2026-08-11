package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 复制边缘矩阵（ADR-0108）：模式×副本、事件类型、滞后、故障容忍。 */
class ReplicationEdgeTest {

    @ParameterizedTest(name = "mode {0} replicas {1}")
    @EnumSource(ReplicationMode.class)
    void modeReplicaMatrix(ReplicationMode mode) throws Exception {
        for (int replicas : new int[]{1, 2, 4}) {
            List<ReplicaSink> sinks = new ArrayList<>();
            for (int i = 0; i < replicas; i++) {
                sinks.add(okSink("r" + i));
            }
            ReplicationPipeline pipeline = new ReplicationPipeline(
                    sinks, mode, 2_000, "r1");
            assertThat(pipeline.replicate(
                    event(0, "k", "v")).join()).isTrue();
        }
    }

    @ParameterizedTest(name = "type {0}")
    @EnumSource(ChangeEvent.EventType.class)
    void eventTypeMatrix(ChangeEvent.EventType type) {
        ReplicaSink sink = okSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        ChangeEvent event = new ChangeEvent(0, type, bytes("k"),
                type == ChangeEvent.EventType.DELETE ? null : bytes("v"),
                type == ChangeEvent.EventType.DELETE, "t0", "r1", 0);
        assertThat(pipeline.replicate(event).join()).isTrue();
    }

    @ParameterizedTest(name = "seq {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void parameterizedSeqBoundaries(long seq) {
        ReplicaSink sink = okSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(new ChangeEvent(seq,
                ChangeEvent.EventType.PUT, bytes("k"), bytes("v"),
                false, "t0", "r1", seq)).join();
    }

    @Test
    void asyncToleratesSinkFailure() {
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(failSink("r2")), ReplicationMode.ASYNC,
                1_000, "r1");
        assertThat(pipeline.replicate(event(0, "k", "v")).join())
                .isTrue();
    }

    @ParameterizedTest(name = "delay {0}")
    @ValueSource(ints = {1, 10, 50})
    void parameterizedLag(int delayMillis) throws Exception {
        ReplicaSink sink = delayedSink("r2", delayMillis);
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.SYNC, 2_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        assertThat(pipeline.lagTracker().state("r2")).isNotNull();
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1, 50, 200})
    void parameterizedKeyVolume(int count) {
        ReplicaSink sink = okSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.SYNC, 5_000, "r1");
        for (int i = 0; i < count; i++) {
            pipeline.replicate(event(i, "k" + i, "v" + i)).join();
        }
        assertThat(pipeline.replicatedCount()).isEqualTo(count);
    }

    @Test
    void conflictDetectorReset() {
        ConflictDetector detector = new ConflictDetector();
        detector.observe(event(0, "k", "v1"), "r1");
        detector.observe(event(1, "k", "v2"), "r2");
        detector.reset();
        assertThat(detector.observe(event(2, "k", "v3"), "r2"))
                .isFalse();
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {0, 1, 1024})
    void parameterizedKeySizes(int size) {
        ReplicaSink sink = okSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(new ChangeEvent(0,
                ChangeEvent.EventType.PUT, new byte[size], bytes("v"),
                false, "t0", "r1", 0)).join();
    }

    @Test
    void syncMixedReplicas() {
        List<ReplicaSink> sinks = List.of(okSink("r2"),
                delayedSink("r3", 5));
        ReplicationPipeline pipeline = new ReplicationPipeline(
                sinks, ReplicationMode.SYNC, 2_000, "r1");
        assertThat(pipeline.replicate(event(0, "k", "v")).join())
                .isTrue();
    }

    private static ChangeEvent event(long seq, String key, String value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                bytes(key), bytes(value), false, "t" + seq, "r1", seq);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ReplicaSink okSink(String id) {
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

    private static ReplicaSink failSink(String id) {
        return new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("down"));
            }

            @Override
            public String replicaId() {
                return id;
            }
        };
    }

    private static ReplicaSink delayedSink(String id, long delayMillis) {
        return new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }

            @Override
            public String replicaId() {
                return id;
            }
        };
    }
}
