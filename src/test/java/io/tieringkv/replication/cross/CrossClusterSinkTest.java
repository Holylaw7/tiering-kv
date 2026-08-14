package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 跨集群目标端（ADR-0321）：LWW 决策 + StorageEngine 应用。 */
class CrossClusterSinkTest {

    private final MemTable storage = MemTable.create();
    private final LwwConflictResolver resolver =
            new LwwConflictResolver();
    private final CrossClusterSink sink =
            new CrossClusterSink(storage, resolver);

    private static ChangeEvent put(long seq, String region,
                                   String key, String value,
                                   long timestamp) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), false,
                "t" + seq, region, timestamp);
    }

    @Test
    void acceptedPutAppliedToStorage() {
        assertThat(sink.apply(put(1, "r1", "k", "v1", 100),
                "cluster-a")).isTrue();
        assertThat(storage.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void rejectedEventNotApplied() {
        sink.apply(put(1, "r1", "k", "v-old", 100), "cluster-a");
        assertThat(sink.apply(put(2, "r2", "k", "v-lost", 50),
                "cluster-b")).isFalse();
        assertThat(storage.get(
                "k".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("v-old".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void deleteEventRemovesValue() {
        sink.apply(put(1, "r1", "k", "v", 100), "cluster-a");
        ChangeEvent delete = new ChangeEvent(2,
                ChangeEvent.EventType.DELETE,
                "k".getBytes(StandardCharsets.UTF_8), null, true,
                "t2", "r1", 200);
        assertThat(sink.apply(delete, "cluster-a")).isTrue();
        assertThat(storage.get(
                "k".getBytes(StandardCharsets.UTF_8))).isNull();
    }
}
