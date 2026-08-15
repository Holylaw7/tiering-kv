package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 复制批量编码（ADR-0333）：长度前缀批量 + 标记位 + CRC。 */
class ReplicationEventCodecBatchTest {

    private static ChangeEvent event(long seq, String key,
                                     String value, boolean deleted) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                value == null ? null
                        : value.getBytes(StandardCharsets.UTF_8),
                deleted, "txn-" + seq, "region-" + (seq % 3),
                seq * 10);
    }

    @Test
    void batchRoundTripPreservesOrderAndFields() throws Exception {
        List<ChangeEvent> events = List.of(
                event(1, "k1", "v1", false),
                event(2, "k2", null, true),
                event(3, "中文键", "中文值", false));
        byte[] bytes = ReplicationEventCodec.encodeBatch(events);
        assertThat(ReplicationEventCodec.isBatch(bytes)).isTrue();
        List<ChangeEvent> restored =
                ReplicationEventCodec.decodeBatch(bytes);
        assertThat(restored).hasSize(3);
        assertThat(restored.get(0)).isEqualTo(events.get(0));
        assertThat(restored.get(1)).isEqualTo(events.get(1));
        assertThat(restored.get(2)).isEqualTo(events.get(2));
    }

    @Test
    void emptyBatchRoundTrip() throws Exception {
        byte[] bytes = ReplicationEventCodec.encodeBatch(List.of());
        assertThat(ReplicationEventCodec.isBatch(bytes)).isTrue();
        assertThat(ReplicationEventCodec.decodeBatch(bytes)).isEmpty();
    }

    @Test
    void singleEventPayloadIsNotBatch() throws Exception {
        byte[] single = ReplicationEventCodec.encode(
                event(1, "k", "v", false));
        assertThat(ReplicationEventCodec.isBatch(single)).isFalse();
    }

    @Test
    void largeBatchRoundTrip() throws Exception {
        List<ChangeEvent> events = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            events.add(event(i, "key-" + i, "value-" + i, i % 7 == 0));
        }
        List<ChangeEvent> restored =
                ReplicationEventCodec.decodeBatch(
                        ReplicationEventCodec.encodeBatch(events));
        assertThat(restored).hasSize(500);
        assertThat(restored.get(499)).isEqualTo(events.get(499));
    }

    @Test
    void corruptedBatchRejected() throws Exception {
        byte[] bytes = ReplicationEventCodec.encodeBatch(
                List.of(event(1, "k", "v", false)));
        bytes[bytes.length - 1] ^= 0x7F;
        assertThatThrownBy(() ->
                ReplicationEventCodec.decodeBatch(bytes))
                .isInstanceOf(Exception.class);
    }

    @Test
    void truncatedBatchRejected() {
        assertThatThrownBy(() ->
                ReplicationEventCodec.decodeBatch(
                        new byte[]{ReplicationEventCodec.BATCH_MARKER}))
                .isInstanceOf(Exception.class);
    }
}
