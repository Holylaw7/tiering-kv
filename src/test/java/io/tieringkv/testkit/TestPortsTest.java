package io.tieringkv.testkit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** TestPorts 并发分配稳定性：并发请求不得返回重复端口。 */
class TestPortsTest {

    @Test
    void concurrentAllocationReturnsDistinctPorts() throws Exception {
        int threads = 16;
        int perThread = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentSkipListSet<Integer> ports = new ConcurrentSkipListSet<>();
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < perThread; i++) {
                            ports.add(TestPorts.freePort());
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(ports).hasSize(threads * perThread);
        } finally {
            pool.shutdownNow();
        }
    }
}
