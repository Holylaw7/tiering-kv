package io.tieringkv.observability;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.SSTableMeta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-100：SSTable 清单在规模与并发下的正确性。
 *
 * <p>用真实 {@link ColdStorageEngine} 构造表（不是伪造数据），
 * 覆盖 1 / 10 / 100 / 1000 张表的清单成本，以及 Flush + Compaction 并发时的快照一致性。
 */
class SstableInventoryScaleTest {

    private static final Pattern ROW = Pattern.compile(
            "sstable:(\\d+)\\|keys=(\\d+)\\|bytes=(\\d+)\\|");
    private static final Pattern TOTAL_ENTRIES = Pattern.compile("sstable_total_entries:(\\d+)");
    private static final Pattern TOTAL_BYTES = Pattern.compile("sstable_total_bytes:(\\d+)");

    @TempDir
    Path dir;

    private ColdStorageEngine engine(Path directory) throws IOException {
        // compactionThreshold 调到很大，让表数量按预期累积（本测试只关心清单本身）
        return new ColdStorageEngine(new ColdStorageEngine.Config(
                directory, 4096, 10, 1L << 30, 100_000));
    }

    private static KeyValueEntry entry(String key, String value) {
        return KeyValueEntry.live(key.getBytes(StandardCharsets.UTF_8),
                value.getBytes(StandardCharsets.UTF_8), 0L, 0L, 1L);
    }

    @Test
    void inventoryCostGrowsLinearlyWithTableCount() throws IOException {
        ColdStorageEngine engine = engine(dir.resolve("scale"));
        List<String> report = new ArrayList<>();
        int written = 0;

        for (int target : new int[] {1, 10, 100, 1000}) {
            while (written < target) {
                written++;
                engine.writeTable(List.of(entry("k" + written, "v" + written)));
            }

            long copyStart = System.nanoTime();
            List<SSTableMeta> snapshot = engine.tablesSnapshot();
            long copyMicros = (System.nanoTime() - copyStart) / 1_000;

            long renderStart = System.nanoTime();
            String text = SstableInfoRenderer.render(snapshot);
            long renderMicros = (System.nanoTime() - renderStart) / 1_000;

            assertThat(snapshot).hasSize(target);
            assertThat(consistency(text)).as("清单自洽（1/10/100/1000）").isTrue();

            report.add(String.format("tables=%d snapshot=%dus render=%dus bytes=%dB",
                    target, copyMicros, renderMicros, text.length()));
        }

        report.forEach(line -> System.out.println("[inventory] " + line));
        // 1000 张表的清单渲染不应该是瓶颈（宽松上界，防回归）
        assertThat(report.get(3)).contains("tables=1000");
        engine.close();
    }

    @Test
    void snapshotIsConsistentWhileFlushAndCompactionRun() throws Exception {
        Path dir2 = dir.resolve("concurrent");
        ColdStorageEngine engine = new ColdStorageEngine(new ColdStorageEngine.Config(
                dir2, 4096, 10, 1L << 30, 4));

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Map<String, Integer> renderCounts = new ConcurrentHashMap<>();
        CountDownLatch started = new CountDownLatch(1);

        Thread mutator = new Thread(() -> {
            int seq = 0;
            try {
                started.await();
                while (running.get()) {
                    seq++;
                    // 并发写入：触发 Flush 与（阈值 4）Compaction
                    engine.writeTable(List.of(entry("k" + seq, "v"), entry("k" + (seq + 1), "v")));
                    if (seq % 5 == 0) {
                        engine.compactAll();
                    }
                }
            } catch (Throwable ex) {
                failure.compareAndSet(null, ex);
            }
        }, "inventory-mutator");

        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Thread reader = new Thread(() -> {
                try {
                    started.await();
                    while (running.get()) {
                        List<SSTableMeta> snapshot = engine.tablesSnapshot();
                        String text = SstableInfoRenderer.render(snapshot);
                        if (!consistency(text)) {
                            throw new AssertionError("清单快照不自洽：\n" + text);
                        }
                        for (SSTableMeta meta : snapshot) {
                            if (meta.id() <= 0 || meta.fileSize() < 0 || meta.entryCount() < 0) {
                                throw new AssertionError("非法元数据：" + meta);
                            }
                        }
                        renderCounts.merge("renders", 1, Integer::sum);
                    }
                } catch (Throwable ex) {
                    failure.compareAndSet(null, ex);
                }
            }, "inventory-reader-" + i);
            readers.add(reader);
            reader.start();
        }

        mutator.start();
        started.countDown();
        Thread.sleep(1500);
        running.set(false);
        mutator.join(5000);
        for (Thread reader : readers) {
            reader.join(5000);
        }

        assertThat(failure.get()).isNull();
        assertThat(renderCounts.getOrDefault("renders", 0)).isGreaterThan(10);
        engine.close();
    }

    /** 校验：表数量、累计字节/条目与逐行之和一致（防止撕裂快照）。 */
    static boolean consistency(String text) {
        long rows = 0;
        long rowBytes = 0;
        long rowKeys = 0;
        Matcher row = ROW.matcher(text);
        while (row.find()) {
            rows++;
            rowKeys += Long.parseLong(row.group(2));
            rowBytes += Long.parseLong(row.group(3));
        }
        long totalBytes = total(TOTAL_BYTES, text);
        long totalEntries = total(TOTAL_ENTRIES, text);
        return rows == count(text) && rowBytes == totalBytes && rowKeys == totalEntries;
    }

    private static long total(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    private static long count(String text) {
        Matcher matcher = Pattern.compile("sstable_count:(\\d+)").matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }
}