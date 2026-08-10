package io.tieringkv.storage.cache;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ARCPolicyTest {

    private final ARCPolicy policy = new ARCPolicy(5);

    @Test
    void missAddsToT1() {
        access("a", 0);
        access("b", 1);
        assertThat(policy.t1Size()).isEqualTo(2);
        assertThat(policy.t2Size()).isZero();
    }

    @Test
    void hitMovesFromT1ToT2() {
        access("a", 0);
        access("b", 1);
        access("a", 2);
        assertThat(policy.t1Size()).isEqualTo(1);
        assertThat(policy.t2Size()).isEqualTo(1);
    }

    @Test
    void t2RefreshKeepsKeyInT2() {
        access("a", 0);
        access("b", 1);
        access("a", 2); // a -> T2
        access("b", 3); // b -> T2
        access("a", 4); // 刷新 T2 内位置
        assertThat(policy.t2Size()).isEqualTo(2);
        assertThat(policy.t1Size()).isZero();
    }

    @Test
    void evictMovesT1KeyToGhostAndB1HitAdaptsP() {
        access("a", 0);
        access("b", 1);
        evict("a", 2);
        assertThat(policy.b1Size()).isEqualTo(1);
        access("a", 3); // B1 hit
        assertThat(policy.t2Size()).isEqualTo(1);
        assertThat(policy.b1Size()).isZero();
        assertThat(policy.targetP()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void evictMovesT2KeyToGhostAndB2HitReturnsToT2() {
        access("x", 0);
        access("y", 1);
        access("x", 2); // x -> T2
        access("y", 3); // y -> T2
        evict("x", 4); // T2 LRU = x -> B2
        assertThat(policy.b2Size()).isEqualTo(1);
        access("x", 5); // B2 hit -> T2, p 不增
        assertThat(policy.t2Size()).isEqualTo(2);
        assertThat(policy.b2Size()).isZero();
    }

    @Test
    void selectCandidatePrefersT1LruWhenT1AboveP() {
        access("a", 0);
        access("b", 1);
        assertThat(candidateKey()).isEqualTo("a");
    }

    @Test
    void selectCandidateFallsBackToT2() {
        access("a", 0);
        access("a", 1); // a -> T2
        assertThat(candidateKey()).isEqualTo("a");
    }

    @Test
    void deleteRemovesFromAllLists() {
        access("a", 0);
        access("b", 1);
        evict("a", 2);
        delete("a", 3);
        assertThat(policy.b1Size()).isZero();
        assertThat(policy.t1Size()).isEqualTo(1);
    }

    private void access(String key, long timestamp) {
        policy.onAccess(new AccessEvent(key.getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.GET, timestamp, 0));
    }

    private void evict(String key, long timestamp) {
        policy.onAccess(new AccessEvent(key.getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.EVICT, timestamp, 0));
    }

    private void delete(String key, long timestamp) {
        policy.onAccess(new AccessEvent(key.getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.DELETE, timestamp, 0));
    }

    private String candidateKey() {
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(candidate).isNotNull();
        return new String(candidate.key(), StandardCharsets.UTF_8);
    }
}
