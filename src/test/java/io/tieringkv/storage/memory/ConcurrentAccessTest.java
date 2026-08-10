package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发安全：100 线程 × 100 操作（≥10k 操作），含共享热点 key。 */
class ConcurrentAccessTest {

    private static final int THREADS = 100;
    private static final int OPS_PER_THREAD = 100;

    @Test
    void hundredThreadsTenThousandOperations() throws Exception {
        MemTable table = MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicLong failures = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < THREADS; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        String key = "k" + threadId + ":" + i;
                        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                        byte[] value = ("v" + threadId + ":" + i).getBytes(StandardCharsets.UTF_8);
                        table.put(keyBytes, value);
                        if (table.get(keyBytes) == null) {
                            failures.incrementAndGet();
                        }
                        if (!table.exists(keyBytes)) {
                            failures.incrementAndGet();
                        }
                        // 共享热点 key：所有线程并发读写同一键
                        byte[] hotKey = "hot".getBytes(StandardCharsets.UTF_8);
                        table.put(hotKey, value);
                        if (table.get(hotKey) == null) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                }
            }));
        }

        start.countDown();
        for (Future<?> future : futures) {
            future.get();
        }

        assertThat(failures).hasValue(0);
        assertThat(table.size()).isEqualTo((long) THREADS * OPS_PER_THREAD + 1);

        // 删除阶段：全部并发删除后归零
        CountDownLatch deleteStart = new CountDownLatch(1);
        List<Future<?>> deleteFutures = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            int threadId = t;
            deleteFutures.add(pool.submit(() -> {
                try {
                    deleteStart.await();
                    for (int i = 0; i < OPS_PER_THREAD; i++) {
                        if (!table.delete(("k" + threadId + ":" + i).getBytes(StandardCharsets.UTF_8))) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                }
            }));
        }
        deleteStart.countDown();
        for (Future<?> future : deleteFutures) {
            future.get();
        }
        table.delete("hot".getBytes(StandardCharsets.UTF_8));
        pool.shutdown();

        assertThat(failures).hasValue(0);
        assertThat(table.size()).isZero();
    }
}
