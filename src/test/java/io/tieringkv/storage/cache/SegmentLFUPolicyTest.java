package io.tieringkv.storage.cache;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 分段 LFU（ADR-0327）：异步缓冲 + 分段索引 + 候选。 */
class SegmentLFUPolicyTest {

    private static AccessEvent event(String key,
                                     AccessEvent.AccessOperation op,
                                     long timestamp) {
        return new AccessEvent(
                key.getBytes(StandardCharsets.UTF_8), op,
                timestamp, 16);
    }

    @Test
    void bufferedEventsDrainIntoSegments() {
        SegmentLFUPolicy policy = new SegmentLFUPolicy(4);
        policy.onAccess(event("a", AccessEvent.AccessOperation.GET, 1));
        policy.onAccess(event("b", AccessEvent.AccessOperation.GET, 1));
        assertThat(policy.buffered()).isEqualTo(2);
        assertThat(policy.drain()).isEqualTo(2);
        assertThat(policy.buffered()).isZero();
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(candidate).isNotNull();
        assertThat(candidate.key()).isNotEmpty();
    }

    @Test
    void selectCandidateDrainsAutomatically() {
        SegmentLFUPolicy policy = new SegmentLFUPolicy(4);
        policy.onAccess(event("a", AccessEvent.AccessOperation.GET, 1));
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(candidate).isNotNull();
        assertThat(policy.buffered()).isZero();
    }

    @Test
    void deleteAndEvictRemoveFromIndex() {
        SegmentLFUPolicy policy = new SegmentLFUPolicy(2);
        policy.onAccess(event("a", AccessEvent.AccessOperation.GET, 1));
        policy.onAccess(event("a", AccessEvent.AccessOperation.GET, 2));
        policy.onAccess(event("b", AccessEvent.AccessOperation.GET, 3));
        policy.onAccess(event("a", AccessEvent.AccessOperation.DELETE, 4));
        policy.onAccess(event("b", AccessEvent.AccessOperation.EVICT, 5));
        policy.drain();
        assertThat(policy.selectCandidate()).isNull();
    }

    @Test
    void highestFrequencyCandidateSelected() {
        SegmentLFUPolicy policy = new SegmentLFUPolicy(4);
        policy.onAccess(event("low", AccessEvent.AccessOperation.GET, 1));
        for (int i = 0; i < 5; i++) {
            policy.onAccess(event("high",
                    AccessEvent.AccessOperation.GET, 2 + i));
        }
        policy.drain();
        // 最低频候选被选出
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(new String(candidate.key(),
                StandardCharsets.UTF_8)).isEqualTo("low");
    }

    @Test
    void invalidSegmentsRejected() {
        assertThatThrownBy(() -> new SegmentLFUPolicy(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
