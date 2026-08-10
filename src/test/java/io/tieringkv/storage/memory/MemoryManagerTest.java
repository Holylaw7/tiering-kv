package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryManagerTest {

    @Test
    void addAndRemoveAccountBytes() {
        MemoryManager manager = new MemoryManager(1024);
        manager.add(100);
        manager.add(50);
        assertThat(manager.usedBytes()).isEqualTo(150);
        manager.remove(60);
        assertThat(manager.usedBytes()).isEqualTo(90);
        manager.remove(1000);
        assertThat(manager.usedBytes()).isZero();
    }

    @Test
    void overLimitDetection() {
        MemoryManager manager = new MemoryManager(100);
        manager.add(99);
        assertThat(manager.isOverLimit()).isFalse();
        manager.add(2);
        assertThat(manager.isOverLimit()).isTrue();
    }

    @Test
    void negativeDeltaRejected() {
        MemoryManager manager = new MemoryManager(100);
        assertThatThrownBy(() -> manager.add(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.remove(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memTableTriggersEvictionCallbackWhenOverLimit() {
        AtomicInteger invocations = new AtomicInteger();
        MemoryManager manager = new MemoryManager(200);
        manager.setEvictionCallback((used, max) -> invocations.incrementAndGet());
        MemTable table = MemTable.createForTest(new MutableClock(0), manager);

        for (int i = 0; i < 10; i++) {
            table.put(("k" + i).getBytes(StandardCharsets.UTF_8), "v".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(invocations).hasValueGreaterThanOrEqualTo(1);
        assertThat(manager.isOverLimit()).isTrue();

        long usedBeforeDelete = manager.usedBytes();
        for (int i = 0; i < 10; i++) {
            table.delete(("k" + i).getBytes(StandardCharsets.UTF_8));
        }
        // tombstone 仍占用内存（ADR-0007），但应显著低于删除前
        assertThat(manager.usedBytes()).isLessThan(usedBeforeDelete);
        assertThat(manager.usedBytes()).isPositive();
    }
}
