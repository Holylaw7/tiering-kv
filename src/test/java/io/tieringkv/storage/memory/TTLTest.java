package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** TTL 混合策略（ADR-0009）：惰性检查 + 主动清扫 + 版本守卫。 */
class TTLTest {

    private final MutableClock clock = new MutableClock(0);
    private MemTable table;

    @BeforeEach
    void setUp() {
        table = MemTable.createForTest(clock, new MemoryManager(1 << 30));
    }

    @Test
    void lazyExpirationOnGet() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v".getBytes(StandardCharsets.UTF_8), 100);
        assertThat(table.get(key)).isNotNull();
        clock.advance(101);
        assertThat(table.get(key)).isNull();
        assertThat(table.exists(key)).isFalse();
    }

    @Test
    void entriesWithoutTtlNeverExpire() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v".getBytes(StandardCharsets.UTF_8));
        clock.advance(100_000);
        assertThat(table.get(key)).isNotNull();
        assertThat(table.exists(key)).isTrue();
    }

    @Test
    void activeExpirationRemovesEntryAndReclaimsMemory() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v".getBytes(StandardCharsets.UTF_8), 100);
        long usedBefore = table.memoryManager().usedBytes();
        clock.advance(101);
        assertThat(table.activeExpire()).isEqualTo(1);
        assertThat(table.size()).isZero();
        assertThat(table.get(key)).isNull();
        assertThat(table.memoryManager().usedBytes()).isZero();
        assertThat(usedBefore).isPositive();
    }

    @Test
    void activeExpirationSkipsNotYetExpiredEntries() {
        table.put("a".getBytes(StandardCharsets.UTF_8), "1".getBytes(StandardCharsets.UTF_8), 1000);
        clock.advance(500);
        assertThat(table.activeExpire()).isZero();
        assertThat(table.get("a".getBytes(StandardCharsets.UTF_8))).isNotNull();
    }

    @Test
    void staleExpiryDoesNotRemoveResetKey() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v1".getBytes(StandardCharsets.UTF_8), 100);
        clock.advance(50);
        table.put(key, "v2".getBytes(StandardCharsets.UTF_8), 1000);
        clock.advance(51); // 旧过期点（100）已到，但新版本过期点为 1050
        assertThat(table.activeExpire()).isZero();
        assertThat(new String(table.get(key), StandardCharsets.UTF_8)).isEqualTo("v2");
        clock.advance(1000);
        assertThat(table.activeExpire()).isEqualTo(1);
        assertThat(table.get(key)).isNull();
    }

    @Test
    void immediateExpiryTtlActsLikeDelete() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        table.put(key, "v".getBytes(StandardCharsets.UTF_8));
        table.put(key, "v2".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(table.get(key)).isNull();
        assertThat(table.size()).isZero();
    }
}
