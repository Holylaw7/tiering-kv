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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 多地域复制（ADR-0108）：async/sync、滞后、冲突、故障。 */
class ReplicationTest {

    @Test
    void asyncReplicateAcksImmediately() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        assertThat(pipeline.replicate(event(0, "k", "v")).join())
                .isTrue();
        assertThat(pipeline.replicatedCount()).isEqualTo(1);
    }

    @Test
    void asyncReplicateDeliversToAll() {
        RecordingSink a = new RecordingSink("r2");
        RecordingSink b = new RecordingSink("r3");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(a, b), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        Thread.yield();
        assertThat(a.events()).hasSize(1);
        assertThat(b.events()).hasSize(1);
    }

    @Test
    void syncReplicateWaitsAllAcks() {
        RecordingSink a = new RecordingSink("r2");
        RecordingSink b = new RecordingSink("r3");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(a, b), ReplicationMode.SYNC, 2_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        assertThat(a.events()).hasSize(1);
        assertThat(b.events()).hasSize(1);
        assertThat(pipeline.replicatedCount()).isEqualTo(1);
    }

    @Test
    void syncTimeoutFails() {
        SlowSink sink = new SlowSink("r2", 500);
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.SYNC, 50, "r1");
        assertThatThrownBy(() -> pipeline.replicate(event(0, "k", "v"))
                .join()).hasCauseInstanceOf(
                java.util.concurrent.TimeoutException.class);
    }

    @Test
    void syncReplicaFailureFails() {
        FailingSink sink = new FailingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.SYNC, 1_000, "r1");
        assertThatThrownBy(() -> pipeline.replicate(event(0, "k", "v"))
                .join()).isInstanceOf(
                java.util.concurrent.CompletionException.class);
    }

    @Test
    void lagTrackerRecordsAppliedSeq() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        pipeline.replicate(event(1, "k2", "v2")).join();
        assertThat(pipeline.lagTracker().state("r2").appliedSeq())
                .isEqualTo(1);
    }

    @Test
    void lagMillisAfterApply() throws Exception {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        assertThat(pipeline.lagTracker().lagMillis("r2",
                System.currentTimeMillis())).isLessThanOrEqualTo(2_000);
    }

    @Test
    void missingStateLagIsMax() {
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(), ReplicationMode.ASYNC, 1_000, "r1");
        assertThat(pipeline.lagTracker().lagMillis("ghost",
                System.currentTimeMillis()))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void conflictSameKeyDifferentOrigin() {
        ConflictDetector detector = new ConflictDetector();
        assertThat(detector.observe(event(0, "k", "v1"), "r1"))
                .isFalse();
        assertThat(detector.observe(event(1, "k", "v2"), "r2"))
                .isTrue();
    }

    @Test
    void sameOriginNotConflict() {
        ConflictDetector detector = new ConflictDetector();
        detector.observe(event(0, "k", "v1"), "r1");
        assertThat(detector.observe(event(1, "k", "v2"), "r1"))
                .isFalse();
    }

    @Test
    void regionMoveResetsOrigin() {
        ConflictDetector detector = new ConflictDetector();
        detector.observe(event(0, "k", "v1"), "r1");
        assertThat(detector.observe(deleteEvent(1, "k"), "r2")).isTrue();
        detector.observe(regionMove(2, "k"), "r2");
        assertThat(detector.observe(event(3, "k", "v3"), "r2"))
                .isFalse();
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100, 500})
    void parameterizedEventCounts(int count) {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        for (int i = 0; i < count; i++) {
            pipeline.replicate(event(i, "k" + i, "v" + i)).join();
        }
        assertThat(sink.events()).hasSize(count);
        assertThat(pipeline.replicatedCount()).isEqualTo(count);
    }

    @ParameterizedTest(name = "replicas {0}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedReplicaCounts(int replicaCount) {
        List<ReplicaSink> sinks = new ArrayList<>();
        List<RecordingSink> recorders = new ArrayList<>();
        for (int i = 0; i < replicaCount; i++) {
            RecordingSink sink = new RecordingSink("r" + (i + 2));
            sinks.add(sink);
            recorders.add(sink);
        }
        ReplicationPipeline pipeline = new ReplicationPipeline(
                sinks, ReplicationMode.SYNC, 2_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        for (RecordingSink sink : recorders) {
            assertThat(sink.events()).hasSize(1);
        }
    }

    @ParameterizedTest(name = "mode {0}")
    @EnumSource(ReplicationMode.class)
    void parameterizedModes(ReplicationMode mode) {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), mode, 1_000, "r1");
        assertThat(pipeline.replicate(event(0, "k", "v")).join())
                .isTrue();
    }

    @Test
    void concurrentReplicateOrderedPerReplica() throws Exception {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.SYNC, 5_000, "r1");
        int writers = 4;
        int perWriter = 25;
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            final int writer = w;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < perWriter; i++) {
                    pipeline.replicate(event(writer * perWriter + i,
                            "k" + i, "v" + i)).join();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(15_000);
        }
        List<ChangeEvent> events = sink.events();
        assertThat(events).hasSize(writers * perWriter);
        // 单生产者内保序；并发生产者间不承诺全局顺序（由 CDC seq 排序）。
        assertThat(events).extracting(ChangeEvent::seq)
                .containsExactlyInAnyOrder(
                        java.util.stream.LongStream.range(0,
                                writers * perWriter).boxed()
                                .toArray(Long[]::new));
    }

    @Test
    void snapshotStatesReturned() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(event(0, "k", "v")).join();
        assertThat(pipeline.lagTracker().snapshot())
                .containsKey("r2");
    }

    @Test
    void asyncDoesNotBlockOnSlowSink() {
        SlowSink sink = new SlowSink("r2", 200);
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        long start = System.nanoTime();
        pipeline.replicate(event(0, "k", "v")).join();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMs).isLessThan(150);
    }

    @Test
    void deletedEventReplicated() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(deleteEvent(0, "k")).join();
        assertThat(sink.events().get(0).deleted()).isTrue();
    }

    @Test
    void txnCommitEventReplicated() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(txnCommit(0, "k")).join();
        assertThat(sink.events().get(0).txnId()).isEqualTo("t0");
    }

    @Test
    void regionMoveEventReplicated() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        pipeline.replicate(regionMove(0, "k")).join();
        assertThat(sink.events().get(0).type())
                .isEqualTo(ChangeEvent.EventType.REGION_MOVE);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {64, 4096})
    void parameterizedValueSizes(int size) {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000, "r1");
        ChangeEvent event = new ChangeEvent(0, ChangeEvent.EventType.PUT,
                bytes("k"), new byte[size], false, "t0", "r1", 0);
        pipeline.replicate(event).join();
        assertThat(sink.events().get(0).value()).hasSize(size);
    }

    @Test
    void zeroReplicasAsyncOk() {
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(), ReplicationMode.ASYNC, 1_000, "r1");
        assertThat(pipeline.replicate(event(0, "k", "v")).join())
                .isTrue();
    }

    @Test
    void zeroReplicasSyncOk() {
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(), ReplicationMode.SYNC, 1_000, "r1");
        assertThat(pipeline.replicate(event(0, "k", "v")).join())
                .isTrue();
    }

    private static ChangeEvent event(long seq, String key, String value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                bytes(key), bytes(value), false, "t" + seq, "r1", seq);
    }

    private static ChangeEvent deleteEvent(long seq, String key) {
        return new ChangeEvent(seq, ChangeEvent.EventType.DELETE,
                bytes(key), null, true, "t" + seq, "r1", seq);
    }

    private static ChangeEvent txnCommit(long seq, String key) {
        return new ChangeEvent(seq, ChangeEvent.EventType.TXN_COMMIT,
                bytes(key), bytes("v"), false, "t" + seq, "r1", seq);
    }

    private static ChangeEvent regionMove(long seq, String key) {
        return new ChangeEvent(seq, ChangeEvent.EventType.REGION_MOVE,
                bytes(key), null, false, null, "r2", seq);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class RecordingSink implements ReplicaSink {
        private final String id;
        private final List<ChangeEvent> events =
                new CopyOnWriteArrayList<>();

        private RecordingSink(String id) {
            this.id = id;
        }

        @Override
        public CompletableFuture<Void> apply(ChangeEvent event) {
            events.add(event);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public String replicaId() {
            return id;
        }

        List<ChangeEvent> events() {
            return List.copyOf(events);
        }
    }

    private static final class SlowSink implements ReplicaSink {
        private final String id;
        private final long delayMillis;

        private SlowSink(String id, long delayMillis) {
            this.id = id;
            this.delayMillis = delayMillis;
        }

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
    }

    private static final class FailingSink implements ReplicaSink {
        private final String id;

        private FailingSink(String id) {
            this.id = id;
        }

        @Override
        public CompletableFuture<Void> apply(ChangeEvent event) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("replica down"));
        }

        @Override
        public String replicaId() {
            return id;
        }
    }
}
