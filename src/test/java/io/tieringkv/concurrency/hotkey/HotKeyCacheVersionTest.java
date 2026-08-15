package io.tieringkv.concurrency.hotkey;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 热缓存版本校验（ADR-0328）：版本新鲜 / 变化刷新 / TTL 兜底。 */
class HotKeyCacheVersionTest {

    private static final HotKeyPolicy POLICY =
            new HotKeyPolicy(1000, 2, 100, 500);

    private static MemTable memTable(MutableClock clock) {
        return MemTable.createForTest(clock,
                new MemoryManager(1 << 30));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    @Test
    void versionFreshHitReturnsCachedWithoutTtlWait() {
        MutableClock clock = new MutableClock(0);
        MemTable storage = memTable(clock);
        storage.put(bytes("k"), bytes("v1"));
        // 预热成热点
        HotKeyDetector detector = new HotKeyDetector(POLICY);
        HotKeyReadCache cache = new HotKeyReadCache(
                detector, POLICY, storage);
        for (int i = 0; i < 5; i++) {
            cache.get(bytes("k"), clock.nowMillis());
        }
        // TTL 500ms：TTL 内命中缓存
        assertThat(text(cache.get(bytes("k"), 100)))
                .isEqualTo("v1");
    }

    @Test
    void versionChangeRefreshesEvenWithinTtl() {
        MutableClock clock = new MutableClock(0);
        MemTable storage = memTable(clock);
        storage.put(bytes("k"), bytes("v1"));
        HotKeyDetector detector = new HotKeyDetector(POLICY);
        HotKeyReadCache cache = new HotKeyReadCache(
                detector, POLICY, storage);
        for (int i = 0; i < 5; i++) {
            cache.get(bytes("k"), 10);
        }
        assertThat(text(cache.get(bytes("k"), 100)))
                .isEqualTo("v1");
        // TTL 内更新：版本变化 → 刷新
        storage.put(bytes("k"), bytes("v2"));
        assertThat(text(cache.get(bytes("k"), 200)))
                .isEqualTo("v2");
    }

    @Test
    void deleteInvalidatesCache() {
        MutableClock clock = new MutableClock(0);
        MemTable storage = memTable(clock);
        storage.put(bytes("k"), bytes("v1"));
        HotKeyDetector detector = new HotKeyDetector(POLICY);
        HotKeyReadCache cache = new HotKeyReadCache(
                detector, POLICY, storage);
        for (int i = 0; i < 5; i++) {
            cache.get(bytes("k"), 10);
        }
        storage.delete(bytes("k"));
        cache.invalidate(bytes("k"));
        assertThat(cache.get(bytes("k"), 200)).isNull();
    }
}
