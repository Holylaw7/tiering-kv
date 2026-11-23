package io.tieringkv.storage.cache;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LFUPolicyTest {

    private final MutableClock clock = new MutableClock(0);
    private final LFUPolicy policy = new LFUPolicy(new HotnessTracker(1000));

    @Test
    void candidateIsLeastFrequentKey() {
        access("a", 0);
        access("b", 0);
        access("a", 1);
        assertThat(candidateKey()).isEqualTo("b");
    }

    @Test
    void tieIsBrokenByOldestAccess() {
        access("x", 0);
        access("y", 10);
        assertThat(candidateKey()).isEqualTo("x");
    }

    @Test
    void putEventUpdatesCandidateSize() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        policy.onAccess(new AccessEvent(key, AccessEvent.AccessOperation.PUT, 0, 100));
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(candidate.sizeBytes()).isEqualTo(100);
        assertThat(candidate.score()).isEqualTo(1);
    }

    @Test
    void deleteRemovesKeyFromCandidateSet() {
        access("a", 0);
        access("b", 1);
        access("a", 2);
        policy.onAccess(new AccessEvent("a".getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.DELETE, 3, 0));
        assertThat(candidateKey()).isEqualTo("b");
    }

    @Test
    void emptyPolicyHasNoCandidate() {
        assertThat(policy.selectCandidate()).isNull();
    }

    @Test
    void nameIsLfu() {
        assertThat(policy.name()).isEqualTo("lfu");
    }

    private void access(String key, long timestamp) {
        policy.onAccess(new AccessEvent(key.getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.GET, timestamp, 0));
    }

    private String candidateKey() {
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(candidate).isNotNull();
        return new String(candidate.key(), StandardCharsets.UTF_8);
    }
}
