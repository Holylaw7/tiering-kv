package io.tieringkv.concurrency.hotkey;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HotKeyDetectorTest {

    private final HotKeyDetector detector =
            new HotKeyDetector(new HotKeyPolicy(1000, 3, 4, 500));

    @Test
    void marksHotAfterThreshold() {
        byte[] key = "hot".getBytes(StandardCharsets.UTF_8);
        assertThat(detector.recordAndCheck(key, 0)).isFalse();
        assertThat(detector.recordAndCheck(key, 10)).isFalse();
        assertThat(detector.recordAndCheck(key, 20)).isTrue();
        assertThat(detector.isHot(key)).isTrue();
    }

    @Test
    void windowRolloverResetsCounter() {
        byte[] key = "hot".getBytes(StandardCharsets.UTF_8);
        detector.recordAndCheck(key, 0);
        detector.recordAndCheck(key, 10);
        detector.recordAndCheck(key, 20);
        assertThat(detector.isHot(key)).isTrue();
        detector.recordAndCheck(key, 1001); // 新窗口：计数重置
        assertThat(detector.hotKeyCount()).isEqualTo(1); // 热点集合仍保留
    }

    @Test
    void invalidateRemovesHotKey() {
        byte[] key = "hot".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            detector.recordAndCheck(key, i);
        }
        assertThat(detector.isHot(key)).isTrue();
        detector.invalidate(key);
        assertThat(detector.isHot(key)).isFalse();
    }

    @Test
    void hotKeySetIsBounded() {
        for (int i = 0; i < 20; i++) {
            byte[] key = ("k" + i).getBytes(StandardCharsets.UTF_8);
            for (int j = 0; j < 3; j++) {
                detector.recordAndCheck(key, j);
            }
        }
        assertThat(detector.hotKeyCount()).isLessThanOrEqualTo(4);
    }
}
