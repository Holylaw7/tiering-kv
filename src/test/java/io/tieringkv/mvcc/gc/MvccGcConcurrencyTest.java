package io.tieringkv.mvcc.gc;

import io.tieringkv.mvcc.MvccGcManager;
import io.tieringkv.mvcc.MvccEntry;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 并发 GC（ADR-0078）：写入与 GC 并行，不得丢最新版本、不得异常。 */
class MvccGcConcurrencyTest {

    @Test
    void concurrentWritersAndGcKeepLatest() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(Long.MAX_VALUE / 2));
        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int writer = w;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 2_000; i++) {
                        byte[] key = bytes("k" + (i % 200));
                        engine.putVersion(key, bytes("w" + writer + "-" + i),
                                i, i + 1, WriteType.PUT);
                    }
                } catch (Throwable t) {
                    failed.set(true);
                    error.set(t);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (int round = 0; round < 20; round++) {
            gc.gc();
            Thread.sleep(1);
        }
        for (Thread thread : threads) {
            thread.join(30_000);
        }
        gc.close();
        assertThat(failed).isFalse();
        assertThat(error.get()).isNull();
        // 每键至少保留一个版本（最新写入可见）
        for (int i = 0; i < 200; i++) {
            assertThat(engine.versions(bytes("k" + i))).isNotEmpty();
        }
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void concurrentGcRunsAreIdempotent() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 500; i++) {
            for (int v = 1; v <= 10; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < 6; w++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 10; round++) {
                        gc.gc();
                    }
                } catch (Throwable t) {
                    failed.set(true);
                }
            });
            threads.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
        }
        gc.close();
        assertThat(failed).isFalse();
        assertThat(engine.versionCount()).isEqualTo(500);
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void concurrentReadsDuringGcSeeConsistentSnapshot() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 1_000; i++) {
            for (int v = 1; v <= 30; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(100));
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean inconsistent = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            try {
                start.await();
                for (int round = 0; round < 5_000; round++) {
                    MvccEntry entry = engine.read(bytes("k" + (round % 1_000)), 100);
                    // safePoint=100：readTS=100 仍可读到 v10（commitTS=100）
                    if (entry == null || entry.value() == null
                            || !new String(entry.value(), StandardCharsets.UTF_8)
                            .equals("v10")) {
                        inconsistent.set(true);
                    }
                }
            } catch (Throwable t) {
                inconsistent.set(true);
            }
        });
        reader.start();
        start.countDown();
        for (int round = 0; round < 20; round++) {
            gc.gc();
        }
        reader.join(30_000);
        gc.close();
        assertThat(inconsistent).isFalse();
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void scheduledGcWithConcurrentWrites() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT);
        gc.updateSafePoint(new SafePoint(1_000_000));
        gc.startScheduled(5);
        AtomicBoolean failed = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 5_000; i++) {
                    engine.putVersion(bytes("k" + (i % 100)), bytes("v" + i),
                            i, i + 1, WriteType.PUT);
                }
            } catch (Throwable t) {
                failed.set(true);
            }
        });
        writer.start();
        writer.join(30_000);
        Thread.sleep(200);
        gc.close();
        assertThat(failed).isFalse();
        for (int i = 0; i < 100; i++) {
            assertThat(engine.versions(bytes("k" + i))).isNotEmpty();
        }
        ((MemTable) engine.underlying()).close();
    }

    @Test
    void metricsUpdatedByBatchGc() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        io.tieringkv.mvcc.MvccMetricsRegistry metrics =
                new io.tieringkv.mvcc.MvccMetricsRegistry();
        for (int i = 0; i < 100; i++) {
            for (int v = 1; v <= 5; v++) {
                engine.putVersion(bytes("k" + i), bytes("v" + v),
                        v, v * 10, WriteType.PUT);
            }
        }
        BatchGcExecutor gc = new BatchGcExecutor(engine, GcConfig.DEFAULT, metrics);
        gc.updateSafePoint(new SafePoint(100));
        gc.gc();
        assertThat(metrics.snapshot().gcVersions()).isEqualTo(100L * 4);
        assertThat(metrics.snapshot().safePoint()).isEqualTo(100);
        assertThat(metrics.snapshot().versions()).isEqualTo(100);
        gc.close();
        ((MemTable) engine.underlying()).close();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
