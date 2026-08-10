package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MemTableTest {

    private final MutableClock clock = new MutableClock(0);
    private final MemTable table = MemTable.createForTest(clock, new MemoryManager(1 << 30));

    @Test
    void putAndGetRoundTrip() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        byte[] value = "value".getBytes(StandardCharsets.UTF_8);
        table.put(key, value);
        assertThat(table.get(key)).isEqualTo(value);
        assertThat(table.exists(key)).isTrue();
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    void overwriteReturnsLatestValue() {
        byte[] key = "key".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v1".getBytes(StandardCharsets.UTF_8));
        table.put(key, "v2".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(table.get(key), StandardCharsets.UTF_8)).isEqualTo("v2");
        assertThat(table.size()).isEqualTo(1);
    }

    @Test
    void getMissingReturnsNull() {
        assertThat(table.get("missing".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(table.exists("missing".getBytes(StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void emptyKeyAndEmptyValueAreSupported() {
        byte[] emptyKey = new byte[0];
        byte[] emptyValue = new byte[0];
        table.put(emptyKey, emptyValue);
        assertThat(table.get(emptyKey)).isEmpty();
    }

    @Test
    void largeValueRoundTrip() {
        byte[] key = "large".getBytes(StandardCharsets.UTF_8);
        byte[] value = new byte[1024 * 1024];
        Arrays.fill(value, (byte) 7);
        table.put(key, value);
        assertThat(table.get(key)).hasSize(1024 * 1024);
        assertThat(table.get(key)).containsOnly((byte) 7);
    }
}
