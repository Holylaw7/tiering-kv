package io.tieringkv.execution;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 压力测试：100 线程 × 100 次同键自增，验证 0 lost update（同键串行）。 */
class ConcurrencyStressTest {

    @Test
    void hundredThreadsNoLostUpdate() throws Exception {
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        byte[] counterKey = "counter".getBytes(StandardCharsets.UTF_8);
        memTable.put(counterKey, "0".getBytes(StandardCharsets.UTF_8));
        AtomicInteger failures = new AtomicInteger();

        try (KeyShardExecutor executor = new KeyShardExecutor(16, "stress-test")) {
            for (int t = 0; t < 100; t++) {
                for (int i = 0; i < 100; i++) {
                    executor.submit(counterKey, () -> {
                        try {
                            byte[] current = memTable.get(counterKey);
                            int value = Integer.parseInt(new String(current, StandardCharsets.UTF_8));
                            memTable.put(counterKey, Integer.toString(value + 1)
                                    .getBytes(StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    });
                }
            }
            assertThat(executor.awaitIdle(30_000)).isTrue();
        }
        assertThat(failures).hasValue(0);
        assertThat(new String(memTable.get(counterKey), StandardCharsets.UTF_8))
                .isEqualTo("10000"); // 无 lost update
    }
}
