package io.tieringkv.storage.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TD-083：堆占用参与超限判定，避免引擎计量低估导致 OOM。 */
class MemoryManagerHeapLimitTest {

    @Test
    void onlyAccountsBytesWhenHeapLimitDisabled() {
        MemoryManager manager = new MemoryManager(1024, 0);
        manager.add(512);
        assertThat(manager.isOverLimit()).isFalse();
        manager.add(1024);
        assertThat(manager.isOverLimit()).isTrue();
    }

    @Test
    void heapPressureAloneMarksOverLimit() {
        // 计时值远低于上限，但堆阈值设成 1 字节 → 必然视为超限
        MemoryManager manager = new MemoryManager(1024L * 1024 * 1024, 1);
        manager.add(16);
        assertThat(manager.usedBytes()).isEqualTo(16);
        assertThat(manager.isOverLimit()).isTrue();
    }

    @Test
    void heapBelowLimitKeepsServing() {
        MemoryManager manager = new MemoryManager(1024L * 1024 * 1024,
                Runtime.getRuntime().maxMemory());
        manager.add(16);
        assertThat(manager.isOverLimit()).isFalse();
    }

    @Test
    void defaultHeapLimitIsPositiveFractionOfMaxHeap() {
        MemoryManager manager = new MemoryManager(1024 * 1024);
        long maxHeap = Runtime.getRuntime().maxMemory();
        assertThat(manager.heapLimitBytes()).isPositive()
                .isLessThanOrEqualTo(maxHeap);
        assertThat(manager.heapUsedBytes()).isPositive();
    }

    @Test
    void rejectsNonPositiveMaxBytes() {
        assertThat(org.junit.jupiter.api.Assertions
                .assertThrows(IllegalArgumentException.class,
                        () -> new MemoryManager(0, 0))).isNotNull();
    }
}
