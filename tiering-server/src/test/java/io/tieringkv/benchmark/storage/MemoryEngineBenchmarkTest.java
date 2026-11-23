package io.tieringkv.benchmark.storage;

import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内存引擎基准（Phase 2）：GET 延迟随数据量（10K/100K/1M）与并发写稳定性
 * （10/50/100 线程）。目标：GET P99 &lt; 0.5ms；100 线程写无失败。
 * 报告输出至 docs/benchmark/memory-engine-report.md。
 */
@Tag("benchmark")
class MemoryEngineBenchmarkTest {

    private static final int[] DATASETS = {10_000, 100_000, 1_000_000};
    private static final int[] THREAD_COUNTS = {10, 50, 100};
    private static final int SAMPLE_COUNT = 50_000;
    private static final int OPS_PER_THREAD = 1000;

    @Test
    void getLatencyByDatasetSize() {
        for (int dataset : DATASETS) {
            try (MemTable table = MemTable.createForTest(
                    System::currentTimeMillis, new MemoryManager(1L << 31))) {
                load(table, dataset);
                for (int i = 0; i < 2000; i++) {
                    table.get(keyFor(ThreadLocalRandom.current().nextInt(dataset)));
                }

                long[] latencies = new long[SAMPLE_COUNT];
                long start = System.nanoTime();
                for (int i = 0; i < SAMPLE_COUNT; i++) {
                    byte[] key = keyFor(ThreadLocalRandom.current().nextInt(dataset));
                    long t0 = System.nanoTime();
                    table.get(key);
                    latencies[i] = System.nanoTime() - t0;
                }
                long totalNanos = System.nanoTime() - start;
                Arrays.sort(latencies);

                double p50 = latencies[SAMPLE_COUNT / 2] / 1_000_000.0;
                double p95 = latencies[(int) (SAMPLE_COUNT * 0.95)] / 1_000_000.0;
                double p99 = latencies[(int) (SAMPLE_COUNT * 0.99)] / 1_000_000.0;
                double opsPerSecond = SAMPLE_COUNT / (totalNanos / 1_000_000_000.0);

                System.out.printf(Locale.ROOT,
                        "MEM-BENCH GET dataset=%d P50=%.4fms P95=%.4fms P99=%.4fms throughput=%.0f ops/s%n",
                        dataset, p50, p95, p99, opsPerSecond);

                assertThat(p99).as("GET P99 dataset=%d", dataset).isLessThan(0.5);
            }
        }
    }

    @Test
    void concurrentWriteStability() throws Exception {
        for (int threads : THREAD_COUNTS) {
            try (MemTable table = MemTable.createForTest(
                    System::currentTimeMillis, new MemoryManager(1L << 31))) {
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                AtomicLong failures = new AtomicLong();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();

                for (int t = 0; t < threads; t++) {
                    int threadId = t;
                    futures.add(pool.submit(() -> {
                        try {
                            start.await();
                            for (int i = 0; i < OPS_PER_THREAD; i++) {
                                table.put(("w:" + threadId + ":" + i).getBytes(StandardCharsets.UTF_8),
                                        new byte[]{(byte) threadId, (byte) i});
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            failures.incrementAndGet();
                        }
                    }));
                }

                long startNanos = System.nanoTime();
                start.countDown();
                for (Future<?> future : futures) {
                    future.get();
                }
                long totalNanos = System.nanoTime() - startNanos;
                pool.shutdown();

                long totalOps = (long) threads * OPS_PER_THREAD;
                double opsPerSecond = totalOps / (totalNanos / 1_000_000_000.0);
                System.out.printf(Locale.ROOT,
                        "MEM-BENCH WRITE threads=%d ops/s=%.0f failures=%d size=%d%n",
                        threads, opsPerSecond, failures.get(), table.size());

                assertThat(failures).hasValue(0);
                assertThat(table.size()).isEqualTo(totalOps);
            }
        }
    }

    private static void load(MemTable table, int count) {
        for (int i = 0; i < count; i++) {
            table.put(keyFor(i), new byte[]{(byte) (i & 0xff), 0, 1});
        }
    }

    private static byte[] keyFor(int index) {
        return String.format(Locale.ROOT, "key:%08d", index).getBytes(StandardCharsets.UTF_8);
    }
}
