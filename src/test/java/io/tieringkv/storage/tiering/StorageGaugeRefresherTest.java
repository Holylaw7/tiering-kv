package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * G0-5B-2 / ADR-0371：冷层 Gauge 刷新与写路径解耦。
 *
 * <p>每个用例对应一条验收项：写路径不再复制表清单、快照原子发布、失败保留旧值、
 * 不重叠、关闭幂等、INFO 不等待。
 */
class StorageGaugeRefresherTest {

    @TempDir
    Path dir;

    private ColdStorageEngine engine() throws Exception {
        return new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, 1, 100));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 刷新后快照包含表数量与磁盘占用() throws Exception {
        try (ColdStorageEngine cold = engine()) {
            for (int i = 0; i < 3; i++) {
                cold.put(KeyValueEntry.live(b("k" + i), b("v"), 0, -1, i));
            }
            StorageMetrics metrics = new StorageMetrics();
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> false);
            refresher.refreshOnce();

            StorageMetrics.Snapshot snapshot = metrics.snapshot();
            assertThat(snapshot.coldSstableCount()).isEqualTo(3);
            assertThat(snapshot.coldDiskUsage()).isPositive();
            assertThat(snapshot.coldEntryCount()).isEqualTo(3);
            assertThat(refresher.refreshCount()).isEqualTo(1);
        }
    }

    @Test
    void 快照整份发布而不是逐字段更新() throws Exception {
        try (ColdStorageEngine cold = engine()) {
            StorageMetrics metrics = new StorageMetrics();
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> true);
            refresher.refreshOnce();
            StorageMetrics.Snapshot a = metrics.snapshot();

            cold.put(KeyValueEntry.live(b("x"), b("v"), 0, -1, 1));
            refresher.refreshOnce();
            StorageMetrics.Snapshot bSnapshot = metrics.snapshot();

            // 一次 snapshot() 内的 flushInProgress 与 coldSstableCount 来自同一份记录
            assertThat(a.flushInProgress()).isEqualTo(1);
            assertThat(bSnapshot.flushInProgress()).isEqualTo(1);
            assertThat(bSnapshot.coldSstableCount()).isGreaterThan(a.coldSstableCount());
        }
    }

    @Test
    void 刷新失败保留上一份快照并计数() throws Exception {
        try (ColdStorageEngine cold = engine()) {
            StorageMetrics failing = new StorageMetrics() {
                @Override
                public void publishColdSnapshot(long a, long b, long c, long d, long e,
                                                long f, long g) {
                    throw new IllegalStateException("publish failed");
                }
            };
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, failing, () -> false);
            refresher.refreshOnce();

            assertThat(refresher.failureCount()).isEqualTo(1);
            assertThat(refresher.refreshCount()).isZero();
            assertThat(failing.snapshot().coldSstableCount()).isZero();
        }
    }

    @Test
    void 刷新不会重叠执行() throws Exception {
        try (ColdStorageEngine cold = engine()) {
            StorageMetrics metrics = new StorageMetrics();
            AtomicBoolean inFlight = new AtomicBoolean();
            AtomicBoolean overlap = new AtomicBoolean();
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> {
                if (inFlight.getAndSet(true)) {
                    overlap.set(true);
                }
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                inFlight.set(false);
                return false;
            });

            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    refresher.refreshOnce();
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(overlap).as("flush supplier 被并发进入").isFalse();
        }
    }

    @Test
    void 线程启动后会周期性刷新且关闭幂等() throws Exception {
        try (ColdStorageEngine cold = engine()) {
            StorageMetrics metrics = new StorageMetrics();
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> false, 50);
            refresher.start();
            cold.put(KeyValueEntry.live(b("k"), b("v"), 0, -1, 1));
            refresher.requestRefresh();

            long deadline = System.currentTimeMillis() + 3000;
            while (metrics.snapshot().coldSstableCount() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertThat(metrics.snapshot().coldSstableCount()).isEqualTo(1);

            refresher.close();
            refresher.close(); // 重复关闭不抛异常
            assertThatCode(refresher::close).doesNotThrowAnyException();
        }
    }

    @Test
    void INFO读取不等待刷新() throws Exception {
        try (ColdStorageEngine cold = engine()) {
            StorageMetrics metrics = new StorageMetrics();
            CountDownLatch blocked = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            StorageGaugeRefresher refresher = new StorageGaugeRefresher(cold, metrics, () -> {
                blocked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return false;
            });

            Thread t = new Thread(refresher::refreshOnce);
            t.start();
            assertThat(blocked.await(2, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            StorageMetrics.Snapshot snapshot = metrics.snapshot(); // INFO 路径：只读快照
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            release.countDown();
            t.join(2000);

            assertThat(snapshot).isNotNull();
            assertThat(elapsedMillis).isLessThan(50);
        }
    }
}