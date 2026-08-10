package io.tieringkv.storage.cache;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HotnessTrackerTest {

    private final MutableClock clock = new MutableClock(0);
    private final HotnessTracker tracker = new HotnessTracker(1000);

    @Test
    void recordIncrementsFrequencyAndTracksLastAccess() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        tracker.record(event(AccessEvent.AccessOperation.GET, key, 0));
        tracker.record(event(AccessEvent.AccessOperation.GET, key, 10));

        HotnessEntry entry = tracker.get(key);
        assertThat(entry.frequency()).isEqualTo(2);
        assertThat(entry.lastAccessTime()).isEqualTo(10);
        assertThat(entry.createTime()).isZero();
    }

    @Test
    void putEventUpdatesSizeBytes() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        tracker.record(new AccessEvent(key, AccessEvent.AccessOperation.PUT, 0, 128));
        assertThat(tracker.get(key).sizeBytes()).isEqualTo(128);
    }

    @Test
    void deleteRemovesEntry() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        tracker.record(event(AccessEvent.AccessOperation.GET, key, 0));
        assertThat(tracker.size()).isEqualTo(1);
        tracker.record(event(AccessEvent.AccessOperation.DELETE, key, 5));
        assertThat(tracker.get(key)).isNull();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void decayAllHalvesFrequencyAfterInterval() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 4; i++) {
            tracker.record(event(AccessEvent.AccessOperation.GET, key, 0));
        }
        assertThat(tracker.get(key).frequency()).isEqualTo(4);
        clock.advance(1001);
        tracker.decayAll(clock.nowMillis());
        assertThat(tracker.get(key).frequency()).isEqualTo(2);
    }

    @Test
    void lazyDecayAppliedOnNextAccess() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 4; i++) {
            tracker.record(event(AccessEvent.AccessOperation.GET, key, 0));
        }
        clock.advance(1001);
        tracker.record(event(AccessEvent.AccessOperation.GET, key, clock.nowMillis()));
        assertThat(tracker.get(key).frequency()).isEqualTo(3); // (4>>1) + 1
    }

    private AccessEvent event(AccessEvent.AccessOperation operation, byte[] key, long timestamp) {
        return new AccessEvent(key, operation, timestamp, 0);
    }
}
