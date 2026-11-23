package io.tieringkv.execution;

import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentReadWriteTest {

    @Test
    void concurrentReadWriteAcrossShardsIsConsistent() throws Exception {
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        AtomicInteger failures = new AtomicInteger();
        try (KeyShardExecutor executor = new KeyShardExecutor(16, "rw-test")) {
            for (int t = 0; t < 8; t++) {
                int threadId = t;
                for (int i = 0; i < 500; i++) {
                    byte[] key = ("k" + threadId + ":" + i).getBytes(StandardCharsets.UTF_8);
                    byte[] value = ("v" + i).getBytes(StandardCharsets.UTF_8);
                    executor.submit(key, () -> {
                        memTable.put(key, value);
                        if (memTable.get(key) == null) {
                            failures.incrementAndGet();
                        }
                        memTable.delete(key);
                    });
                }
            }
            assertThat(executor.awaitIdle(20_000)).isTrue();
        }
        assertThat(failures).hasValue(0);
        assertThat(memTable.size()).isZero();
    }
}
