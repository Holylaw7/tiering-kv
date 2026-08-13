package io.tieringkv.distributed.harness;

import io.tieringkv.distributed.LinearizabilityChecker;
import io.tieringkv.distributed.LinearizabilityChecker.Operation;
import io.tieringkv.distributed.LinearizabilityChecker.OpType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jepsen 式验证 harness（ADR-0306）：并发客户端生成历史 →
 * LinearizabilityChecker 校验 → 报告。可独立进程运行（main）。
 */
public final class VerificationHarness {

    private final int threads;
    private final int opsPerThread;
    private final String key;
    private final Object lock = new Object();

    public VerificationHarness(int threads, int opsPerThread,
                               String key) {
        if (threads <= 0 || opsPerThread <= 0 || key == null) {
            throw new IllegalArgumentException(
                    "threads/ops/key required");
        }
        this.threads = threads;
        this.opsPerThread = opsPerThread;
        this.key = key;
    }

    public record Report(int operations,
                         boolean linearizable,
                         long elapsedMs) {
    }

    /** 运行：并发 PUT/GET 生成历史并校验。 */
    public Report run() throws Exception {
        ConcurrentHashMap<String, String> store =
                new ConcurrentHashMap<>();
        List<Operation> history = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        synchronized (lock) {
                            long invoke = clock.incrementAndGet();
                            if ((threadId + i) % 3 == 0) {
                                store.put(key, "v" + threadId
                                        + "-" + i);
                                history.add(new Operation(invoke,
                                        clock.incrementAndGet(),
                                        OpType.PUT, key,
                                        "v" + threadId + "-" + i,
                                        null));
                            } else {
                                String value = store.get(key);
                                history.add(new Operation(invoke,
                                        clock.incrementAndGet(),
                                        OpType.GET, key, null,
                                        value));
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long begin = System.nanoTime();
        start.countDown();
        done.await(120, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        pool.shutdownNow();
        boolean linearizable =
                LinearizabilityChecker.isLinearizable(history);
        return new Report(history.size(), linearizable, elapsedMs);
    }

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0
                ? Integer.parseInt(args[0]) : 4;
        int ops = args.length > 1
                ? Integer.parseInt(args[1]) : 200;
        VerificationHarness harness = new VerificationHarness(
                threads, ops, "k");
        Report report = harness.run();
        System.out.printf(
                "HARNESS operations=%d linearizable=%s elapsedMs=%d%n",
                report.operations(), report.linearizable(),
                report.elapsedMs());
        if (!report.linearizable()) {
            System.exit(1);
        }
    }
}
