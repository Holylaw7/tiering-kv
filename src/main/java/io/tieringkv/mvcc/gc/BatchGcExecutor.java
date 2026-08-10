package io.tieringkv.mvcc.gc;

import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.MvccEntry;
import io.tieringkv.mvcc.MvccGcManager;
import io.tieringkv.mvcc.MvccMetricsRegistry;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.WriteType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 批量 GC 执行器（ADR-0078）：扫描 → 分组 → 规划（保留最新且
 * commitTS >= safePoint，跳过 LOCK）→ 并行批量删除。
 * 旧版单版本删除路径吞吐仅 19–29MB/s，本实现目标 >100MB/s。
 */
public final class BatchGcExecutor implements AutoCloseable {

    private final MvccStorageEngine engine;
    private final GcConfig config;
    private final MvccMetricsRegistry metrics;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile SafePoint safePoint = SafePoint.NONE;

    public BatchGcExecutor(MvccStorageEngine engine, GcConfig config) {
        this(engine, config, null);
    }

    public BatchGcExecutor(MvccStorageEngine engine, GcConfig config,
                           MvccMetricsRegistry metrics) {
        this.engine = engine;
        this.config = config;
        this.metrics = metrics;
        this.workers = Executors.newFixedThreadPool(config.workerCount(), r -> {
            Thread thread = new Thread(r, "mvcc-gc-worker");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mvcc-gc-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void updateSafePoint(SafePoint point) {
        this.safePoint = point;
    }

    public SafePoint safePoint() {
        return safePoint;
    }

    /** 执行一轮批量 GC；返回 (回收版本数, 回收字节数)。 */
    public MvccGcManager.GcResult gc() {
        // 直接基于内存版本索引规划，避免底层全表扫描 + MvccKey 解码
        Map<ByteKey, List<MvccEntry>> snapshot = engine.versionGroups();
        long collected = 0;
        long bytes = 0;
        Map<ByteKey, List<MvccEntry>> planned = new HashMap<>();
        long plannedBytes = 0;
        for (Map.Entry<ByteKey, List<MvccEntry>> entry : snapshot.entrySet()) {
            List<MvccEntry> versions = entry.getValue();
            List<MvccEntry> doomed = null;
            // 版本链按 commitTS 升序维护；保留最新版本，LOCK 由回滚/恢复负责
            for (int i = 0; i < versions.size() - 1; i++) {
                MvccEntry version = versions.get(i);
                if (version.writeType() != WriteType.LOCK
                        && safePoint.canCollect(version.commitTS())) {
                    if (doomed == null) {
                        doomed = new ArrayList<>();
                    }
                    doomed.add(version);
                    plannedBytes += entryBytes(version.keyBytes(),
                            version.valueBytes());
                }
            }
            if (doomed != null) {
                planned.put(entry.getKey(), doomed);
                if (plannedBytes >= config.maxMemoryBytes()) {
                    MvccGcManager.GcResult result = executeGroups(planned);
                    collected += result.collectedVersions();
                    bytes += result.collectedBytes();
                    planned = new HashMap<>();
                    plannedBytes = 0;
                }
            }
        }
        if (!planned.isEmpty()) {
            MvccGcManager.GcResult result = executeGroups(planned);
            collected += result.collectedVersions();
            bytes += result.collectedBytes();
        }
        if (metrics != null) {
            metrics.recordGc(collected, bytes);
            metrics.setVersions(engine.versionCount());
            metrics.setSafePoint(safePoint.timestamp());
        }
        return new MvccGcManager.GcResult(collected, bytes);
    }

    public void startScheduled(long intervalMillis) {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(() -> {
                if (safePoint != SafePoint.NONE) {
                    gc();
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    private MvccGcManager.GcResult executeGroups(
            Map<ByteKey, List<MvccEntry>> planned) {
        long collected = executeParallel(planned);
        long bytes = 0;
        for (List<MvccEntry> versions : planned.values()) {
            for (MvccEntry version : versions) {
                bytes += entryBytes(version.keyBytes(), version.valueBytes());
            }
        }
        return new MvccGcManager.GcResult(collected, bytes);
    }

    private long executeParallel(Map<ByteKey, List<MvccEntry>> planned) {
        if (planned.isEmpty()) {
            return 0;
        }
        List<Map.Entry<ByteKey, List<MvccEntry>>> entries =
                new ArrayList<>(planned.entrySet());
        int parallel = Math.min(config.workerCount(), entries.size());
        if (parallel <= 1) {
            return engine.deleteVersionGroups(planned);
        }
        int chunk = (entries.size() + parallel - 1) / parallel;
        List<CompletableFuture<Long>> futures = new ArrayList<>(parallel);
        for (int i = 0; i < parallel; i++) {
            int from = i * chunk;
            int to = Math.min(from + chunk, entries.size());
            Map<ByteKey, List<MvccEntry>> partition = new HashMap<>();
            for (int j = from; j < to; j++) {
                partition.put(entries.get(j).getKey(), entries.get(j).getValue());
            }
            futures.add(CompletableFuture.supplyAsync(
                    () -> {
                        long removed = 0;
                        List<Map.Entry<ByteKey, List<MvccEntry>>> batchEntries =
                                new ArrayList<>(partition.entrySet());
                        for (int j = 0; j < batchEntries.size();
                             j += config.batchSize()) {
                            Map<ByteKey, List<MvccEntry>> batch = new HashMap<>();
                            for (int k = j; k < Math.min(
                                    j + config.batchSize(), batchEntries.size());
                                 k++) {
                                batch.put(batchEntries.get(k).getKey(),
                                        batchEntries.get(k).getValue());
                            }
                            removed += engine.deleteVersionGroups(batch);
                        }
                        return removed;
                    }, workers));
        }
        long removed = 0;
        for (CompletableFuture<Long> future : futures) {
            removed += future.join();
        }
        return removed;
    }

    private static long entryBytes(byte[] key, byte[] value) {
        return key.length + (value == null ? 0 : value.length);
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
