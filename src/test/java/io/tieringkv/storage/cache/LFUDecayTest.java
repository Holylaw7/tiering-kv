package io.tieringkv.storage.cache;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LFUDecayTest {

    private final MutableClock clock = new MutableClock(0);
    private final HotnessTracker tracker = new HotnessTracker(1000);

    @Test
    void frequencyHalvesAfterOneInterval() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        recordAccesses(key, 4, 0);
        clock.advance(1001);
        recordAccesses(key, 1, clock.nowMillis());
        assertThat(tracker.get(key).frequency()).isEqualTo(3);
    }

    @Test
    void multipleIntervalsHalveRepeatedly() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        recordAccesses(key, 8, 0);
        clock.advance(3001);
        recordAccesses(key, 1, clock.nowMillis());
        assertThat(tracker.get(key).frequency()).isEqualTo(2); // (8>>3) + 1
    }

    @Test
    void noDecayBeforeInterval() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        recordAccesses(key, 4, 0);
        clock.advance(999);
        recordAccesses(key, 1, clock.nowMillis());
        assertThat(tracker.get(key).frequency()).isEqualTo(5);
    }

    @Test
    void decayAllAppliesToAllEntries() {
        byte[] keyA = "a".getBytes(StandardCharsets.UTF_8);
        byte[] keyB = "b".getBytes(StandardCharsets.UTF_8);
        recordAccesses(keyA, 6, 0);
        recordAccesses(keyB, 10, 0);
        clock.advance(1001);
        tracker.decayAll(clock.nowMillis());
        assertThat(tracker.get(keyA).frequency()).isEqualTo(3);
        assertThat(tracker.get(keyB).frequency()).isEqualTo(5);
    }

    private void recordAccesses(byte[] key, int count, long timestamp) {
        for (int i = 0; i < count; i++) {
            tracker.record(new AccessEvent(key, AccessEvent.AccessOperation.GET, timestamp, 0));
        }
    }
}
