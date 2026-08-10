package io.tieringkv.storage.wal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WALEntryTest {

    @Test
    void putRecordRoundTrip() {
        WALEntry entry = WALEntry.put(
                1234L,
                "key".getBytes(StandardCharsets.UTF_8),
                "value".getBytes(StandardCharsets.UTF_8),
                5000L,
                7L);
        assertThat(WALRecord.decode(WALRecord.encode(entry))).isEqualTo(entry);
    }

    @Test
    void deleteRecordRoundTrip() {
        WALEntry entry = WALEntry.delete(99L, "gone".getBytes(StandardCharsets.UTF_8), 3L);
        assertThat(WALRecord.decode(WALRecord.encode(entry))).isEqualTo(entry);
        assertThat(entry.value()).isNull();
        assertThat(entry.ttlMillis()).isEqualTo(-1);
    }

    @Test
    void binaryPayloadRoundTrip() {
        byte[] key = new byte[]{'k', '\r', '\n', 0, (byte) 0xff};
        byte[] value = new byte[]{'v', 0, 1, 2, (byte) 0x80};
        WALEntry entry = WALEntry.put(5L, key, value, -1, 1L);
        WALEntry decoded = WALRecord.decode(WALRecord.encode(entry));
        assertThat(decoded.key()).isEqualTo(key);
        assertThat(decoded.value()).isEqualTo(value);
    }

    @Test
    void badMagicRejected() {
        byte[] record = WALRecord.encode(WALEntry.put(1L, "k".getBytes(), "v".getBytes(), -1, 1L));
        record[0] ^= 0x55;
        assertThatThrownBy(() -> WALRecord.decode(record)).isInstanceOf(WalCorruptionException.class);
    }

    @Test
    void badVersionRejected() {
        byte[] record = WALRecord.encode(WALEntry.put(1L, "k".getBytes(), "v".getBytes(), -1, 1L));
        record[4] = 99;
        assertThatThrownBy(() -> WALRecord.decode(record)).isInstanceOf(WalCorruptionException.class);
    }

    @Test
    void payloadTamperingDetectedByChecksum() {
        byte[] record = WALRecord.encode(WALEntry.put(1L, "key".getBytes(), "value".getBytes(), -1, 1L));
        record[42] ^= 0x01;
        assertThatThrownBy(() -> WALRecord.decode(record)).isInstanceOf(WalCorruptionException.class);
    }

    @Test
    void shortRecordRejected() {
        assertThatThrownBy(() -> WALRecord.decode(new byte[10]))
                .isInstanceOf(WalCorruptionException.class);
    }
}
