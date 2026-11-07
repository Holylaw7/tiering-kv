package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G0-5B-1 / ADR-0370：冷层存活键计数快照。
 *
 * <p>重点不是"数字对不对"，而是：INFO 读到的值来自快照（O(1)），
 * 且快照必须与真实存储路径（pending / flush / compaction / 重启）保持一致。
 */
class ColdLiveCountTest {

    @TempDir
    Path dir;

    private ColdStorageEngine engine(int pendingThreshold, int compactionThreshold) throws Exception {
        return new ColdStorageEngine(new ColdStorageEngine.Config(
                dir, 4096, 10, pendingThreshold, compactionThreshold));
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void 新增键后快照增加() throws Exception {
        try (ColdStorageEngine cold = engine(1 << 20, 8)) {
            for (int i = 0; i < 5; i++) {
                cold.put(KeyValueEntry.live(b("k" + i), b("v"), 0, -1, i));
            }
            assertThat(cold.liveCountStale()).isTrue();
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(5);
        }
    }

    @Test
    void 覆盖已有键计数不变() throws Exception {
        try (ColdStorageEngine cold = engine(1 << 20, 8)) {
            cold.put(KeyValueEntry.live(b("k"), b("v1"), 0, -1, 1));
            cold.refreshLiveCount();
            cold.put(KeyValueEntry.live(b("k"), b("v2"), 0, -1, 2));
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(1);
        }
    }

    @Test
    void 删除有效键计数减少且重复删除不重复扣减() throws Exception {
        try (ColdStorageEngine cold = engine(1 << 20, 8)) {
            cold.put(KeyValueEntry.live(b("a"), b("v"), 0, -1, 1));
            cold.put(KeyValueEntry.live(b("b"), b("v"), 0, -1, 2));
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(2);
            cold.delete(b("a"));
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(1);
            cold.delete(b("a"));
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(1);
        }
    }

    @Test
    void tombstone与新版本共存时计数正确() throws Exception {
        try (ColdStorageEngine cold = engine(1, 100)) {
            cold.put(KeyValueEntry.live(b("x"), b("v1"), 0, -1, 1));
            cold.delete(b("x"));
            cold.put(KeyValueEntry.live(b("y"), b("v"), 0, -1, 2));
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(1);
        }
    }

    @Test
    void TTL过期后计数不包含该键() throws Exception {
        try (ColdStorageEngine cold = engine(1 << 20, 8)) {
            long now = System.currentTimeMillis();
            cold.put(KeyValueEntry.live(b("ttl"), b("v"), now, 60, 1));
            cold.put(KeyValueEntry.live(b("keep"), b("v"), now, -1, 2));
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(2);
            Thread.sleep(80);
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(1);
        }
    }

    @Test
    void Flush前后计数一致() throws Exception {
        try (ColdStorageEngine cold = engine(1, 100)) {
            for (int i = 0; i < 6; i++) {
                cold.put(KeyValueEntry.live(b("k" + i), b("v"), 0, -1, i));
            }
            cold.refreshLiveCount();
            long before = cold.liveCount();
            cold.compactAll();
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(before).isEqualTo(6);
        }
    }

    @Test
    void 重启后计数恢复() throws Exception {
        ColdStorageEngine.Config config = new ColdStorageEngine.Config(dir, 4096, 10, 1, 100);
        try (ColdStorageEngine cold = new ColdStorageEngine(config)) {
            for (int i = 0; i < 5; i++) {
                cold.put(KeyValueEntry.live(b("r" + i), b("v"), 0, -1, i));
            }
            cold.delete(b("r0"));
        }
        try (ColdStorageEngine restarted = new ColdStorageEngine(config)) {
            assertThat(restarted.liveCountKnown()).isTrue();
            assertThat(restarted.liveCount()).isEqualTo(4);
        }
    }

    @Test
    void INFO读取不触发扫描而是返回快照() throws Exception {
        try (ColdStorageEngine cold = engine(1 << 20, 8)) {
            cold.put(KeyValueEntry.live(b("a"), b("v"), 0, -1, 1));
            cold.refreshLiveCount();
            cold.put(KeyValueEntry.live(b("b"), b("v"), 0, -1, 2));
            // 写入后未刷新：liveCount 必须返回旧快照（证明没有在读取路径重算）
            assertThat(cold.liveCount()).isEqualTo(1);
            assertThat(cold.liveCountStale()).isTrue();
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(2);
        }
    }

    @Test
    void 并发写入与删除后刷新计数一致() throws Exception {
        try (ColdStorageEngine cold = engine(1 << 20, 8)) {
            int threads = 4;
            int perThread = 250;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch done = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                final int base = t * perThread;
                pool.submit(() -> {
                    try {
                        for (int i = 0; i < perThread; i++) {
                            cold.put(KeyValueEntry.live(b("c" + (base + i)), b("v"), 0, -1, i));
                        }
                        for (int i = 0; i < 50; i++) {
                            cold.delete(b("c" + (base + i)));
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
            pool.shutdownNow();
            cold.refreshLiveCount();
            assertThat(cold.liveCount()).isEqualTo(threads * (perThread - 50));
        }
    }
}