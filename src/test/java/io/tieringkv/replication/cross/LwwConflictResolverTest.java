package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** LWW 冲突决策（ADR-0321）：时间戳 / cluster 序 / seq 幂等。 */
class LwwConflictResolverTest {

    private static ChangeEvent event(long seq, String region,
                                     String key, long timestamp) {
        return new ChangeEvent(seq, ChangeEvent.EventType.PUT,
                key.getBytes(StandardCharsets.UTF_8),
                ("v-" + timestamp).getBytes(StandardCharsets.UTF_8),
                false, "t" + seq, region, timestamp);
    }

    @Test
    void higherTimestampWins() {
        LwwConflictResolver resolver = new LwwConflictResolver();
        assertThat(resolver.accept(
                event(1, "r1", "k", 100), "cluster-a")).isTrue();
        assertThat(resolver.accept(
                event(2, "r2", "k", 200), "cluster-b")).isTrue();
        assertThat(resolver.accept(
                event(3, "r3", "k", 150), "cluster-c")).isFalse();
        assertThat(resolver.appliedSize()).isEqualTo(1);
    }

    @Test
    void sameTimestampResolvedByClusterOrder() {
        LwwConflictResolver resolver = new LwwConflictResolver();
        assertThat(resolver.accept(
                event(1, "r1", "k", 100), "cluster-a")).isTrue();
        // cluster-b > cluster-a：后写者胜
        assertThat(resolver.accept(
                event(2, "r2", "k", 100), "cluster-b")).isTrue();
        // cluster-0 < cluster-b：被裁决
        assertThat(resolver.accept(
                event(3, "r3", "k", 100), "cluster-0")).isFalse();
    }

    @Test
    void sameSourceSeqIsIdempotent() {
        LwwConflictResolver resolver = new LwwConflictResolver();
        assertThat(resolver.accept(
                event(5, "r1", "k", 100), "cluster-a")).isTrue();
        assertThat(resolver.accept(
                event(5, "r1", "k", 100), "cluster-a")).isFalse();
        assertThat(resolver.accept(
                event(4, "r1", "k", 999), "cluster-a")).isFalse();
    }

    @Test
    void differentRegionsSeqIndependent() {
        LwwConflictResolver resolver = new LwwConflictResolver();
        assertThat(resolver.accept(
                event(1, "r1", "k", 100), "cluster-a")).isTrue();
        // 不同 region 同 timestamp：cluster-b 胜
        assertThat(resolver.accept(
                event(1, "r2", "k", 100), "cluster-b")).isTrue();
        // 更低 cluster 序被裁决（seq 跨源不互斥）
        assertThat(resolver.accept(
                event(1, "r3", "k", 100), "cluster-0")).isFalse();
        assertThat(resolver.appliedSize()).isEqualTo(1);
    }

    @Test
    void deleteEventParticipatesInLww() {
        LwwConflictResolver resolver = new LwwConflictResolver();
        assertThat(resolver.accept(
                event(1, "r1", "k", 100), "cluster-a")).isTrue();
        ChangeEvent delete = new ChangeEvent(2,
                ChangeEvent.EventType.DELETE,
                "k".getBytes(StandardCharsets.UTF_8), null, true,
                "t2", "r2", 200);
        assertThat(resolver.accept(delete, "cluster-b")).isTrue();
    }

    @Test
    void nullOriginRejected() {
        LwwConflictResolver resolver = new LwwConflictResolver();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> resolver.accept(
                        event(1, "r1", "k", 1), null));
    }
}
