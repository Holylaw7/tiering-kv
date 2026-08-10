package io.tieringkv.concurrency;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCoalescingTest {

    @Test
    void concurrentRequestsShareSingleLoader() throws Exception {
        RequestCoalescer coalescer = new RequestCoalescer();
        AtomicInteger loads = new AtomicInteger();
        ByteBuffer key = ByteBuffer.wrap("k".getBytes(StandardCharsets.UTF_8));
        int threads = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        List<byte[]> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    byte[] value = coalescer.coalesce(key, () -> {
                        loads.incrementAndGet();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return "value".getBytes(StandardCharsets.UTF_8);
                    });
                    results.add(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(5000);
        }
        assertThat(loads).hasValue(1); // 10000 请求 → 1 次读取
        assertThat(results).hasSize(threads);
    }

    @Test
    void sequentialCallsLoadEachTime() {
        RequestCoalescer coalescer = new RequestCoalescer();
        AtomicInteger loads = new AtomicInteger();
        ByteBuffer key = ByteBuffer.wrap("k".getBytes(StandardCharsets.UTF_8));
        coalescer.coalesce(key, () -> {
            loads.incrementAndGet();
            return new byte[]{1};
        });
        coalescer.coalesce(key, () -> {
            loads.incrementAndGet();
            return new byte[]{2};
        });
        assertThat(loads).hasValue(2);
    }
}
