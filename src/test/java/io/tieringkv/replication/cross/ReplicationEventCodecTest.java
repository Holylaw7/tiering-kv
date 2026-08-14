package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 复制事件编码（ADR-0321）：roundtrip / 损坏拒绝。 */
class ReplicationEventCodecTest {

    private static ChangeEvent event(long seq, ChangeEvent.EventType type,
                                     String key, String value,
                                     boolean deleted) {
        return new ChangeEvent(seq, type,
                key.getBytes(StandardCharsets.UTF_8),
                value == null ? null
                        : value.getBytes(StandardCharsets.UTF_8),
                deleted, "t" + seq, "r1", seq * 1_000);
    }

    @Test
    void putEventRoundTrip() throws Exception {
        ChangeEvent original = event(7, ChangeEvent.EventType.PUT,
                "k1", "v1", false);
        ChangeEvent restored = ReplicationEventCodec.decode(
                ReplicationEventCodec.encode(original));
        assertThat(restored).isEqualTo(original);
        assertThat(restored.timestamp()).isEqualTo(7_000);
    }

    @Test
    void deleteEventRoundTrip() throws Exception {
        ChangeEvent original = event(8, ChangeEvent.EventType.DELETE,
                "k2", null, true);
        ChangeEvent restored = ReplicationEventCodec.decode(
                ReplicationEventCodec.encode(original));
        assertThat(restored).isEqualTo(original);
        assertThat(restored.value()).isNull();
    }

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"中文键", "a b", ""})
    void unicodeAndLongKeysRoundTrip(String key) throws Exception {
        ChangeEvent original = event(9, ChangeEvent.EventType.PUT,
                key, "v", false);
        assertThat(ReplicationEventCodec.decode(
                ReplicationEventCodec.encode(original)))
                .isEqualTo(original);
    }

    @Test
    void longKeyRoundTrip() throws Exception {
        String key = "x".repeat(300);
        ChangeEvent original = event(10, ChangeEvent.EventType.PUT,
                key, "v", false);
        assertThat(ReplicationEventCodec.decode(
                ReplicationEventCodec.encode(original)))
                .isEqualTo(original);
    }

    @Test
    void corruptedPayloadRejectedByCrc() throws Exception {
        byte[] encoded = ReplicationEventCodec.encode(
                event(1, ChangeEvent.EventType.PUT, "k", "v", false));
        encoded[encoded.length - 5] ^= 0x7F;
        assertThatThrownBy(() -> ReplicationEventCodec.decode(encoded))
                .hasMessageContaining("CRC");
    }

    @Test
    void shortPayloadRejected() {
        assertThatThrownBy(() ->
                ReplicationEventCodec.decode(new byte[]{1, 2, 3}))
                .hasMessageContaining("too short");
    }
}
