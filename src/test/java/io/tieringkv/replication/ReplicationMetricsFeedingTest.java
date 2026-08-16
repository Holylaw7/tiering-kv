package io.tieringkv.replication;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.observability.ReplicationMetricsRegistry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 复制喂数（ADR-0345）：pipeline 注入 ReplicationMetricsRegistry。 */
class ReplicationMetricsFeedingTest {

    @Test
    void replicationPipelineFeedsReplicatedAndLag() {
        RecordingSink sink = new RecordingSink("r2");
        ReplicationMetricsRegistry metrics =
                new ReplicationMetricsRegistry();
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.ASYNC, 1_000,
                "r1", metrics);
        pipeline.replicate(event(0, "k", "v")).join();
        pipeline.replicate(event(1, "k2", "v2")).join();

        assertThat(metrics.snapshot(
                System.currentTimeMillis()).replicated()).isEqualTo(2);
        // attach 内部 LagTracker：复制成功后水位可见
        assertThat(metrics.snapshot(
                System.currentTimeMillis()).replicas()).isEqualTo(1);
    }

    @Test
    void bidirectionalPipelineFeedsAllCounters() {
        RecordingSink peer = new RecordingSink("node2");
        ReplicationMetricsRegistry metrics =
                new ReplicationMetricsRegistry();
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(peer), "node1", 1_000, metrics);

        pipeline.write(bytes("k"), bytes("v")).join();
        assertThat(metrics.snapshot(
                System.currentTimeMillis()).replicated()).isEqualTo(1);

        // 不同节点写同 key → LWW 冲突计数
        pipeline.receive(bytes("k"), bytes("v2"), "node2", 2);
        assertThat(metrics.snapshot(
                System.currentTimeMillis()).conflicts()).isEqualTo(1);

        // 已见事件 → 抑制计数
        pipeline.receive(bytes("k"), bytes("v2"), "node2", 2);
        assertThat(metrics.snapshot(
                System.currentTimeMillis()).suppressed()).isEqualTo(1);
    }

    private static ChangeEvent event(long seq, String key, String value) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                bytes(key), bytes(value), false, "t" + seq,
                "r1", System.currentTimeMillis());
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
