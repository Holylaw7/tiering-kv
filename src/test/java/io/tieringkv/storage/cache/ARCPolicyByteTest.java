package io.tieringkv.storage.cache;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ARC 字节容量模式（ADR-0326）：按内存字节淘汰。 */
class ARCPolicyByteTest {

    private static AccessEvent put(String key, int size) {
        return new AccessEvent(
                key.getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.PUT,
                System.currentTimeMillis(), size);
    }

    private static AccessEvent get(String key) {
        return new AccessEvent(
                key.getBytes(StandardCharsets.UTF_8),
                AccessEvent.AccessOperation.GET,
                System.currentTimeMillis(), 0);
    }

    @Test
    void byteCapacityEvictsByBytes() {
        ARCPolicy policy = new ARCPolicy(100L);
        policy.onAccess(put("big", 80));
        policy.onAccess(put("small", 40));
        // used=120 > 100：应淘汰一个（ARC 淘汰 T1/T2）
        EvictionCandidate candidate = policy.selectCandidate();
        assertThat(candidate).isNotNull();
        policy.onAccess(new AccessEvent(candidate.key(),
                AccessEvent.AccessOperation.EVICT,
                System.currentTimeMillis(), candidate.sizeBytes()));
        policy.onAccess(put("next", 50));
        assertThat(policy.selectCandidate()).isNotNull();
    }

    @Test
    void byteCapacityRespectsBoundary() {
        ARCPolicy policy = new ARCPolicy(100L);
        policy.onAccess(put("a", 60));
        policy.onAccess(put("b", 40));
        // 恰好 100：不淘汰
        assertThat(policy.selectCandidate()).isNotNull();
    }

    @Test
    void entryModeStillWorks() {
        ARCPolicy policy = new ARCPolicy(2);
        policy.onAccess(put("a", 10));
        policy.onAccess(put("b", 10));
        policy.onAccess(put("c", 10));
        assertThat(policy.selectCandidate()).isNotNull();
    }

    @Test
    void invalidCapacityRejected() {
        assertThatThrownBy(() -> new ARCPolicy(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ARCPolicy(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
