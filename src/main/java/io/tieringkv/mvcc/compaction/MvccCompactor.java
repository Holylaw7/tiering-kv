package io.tieringkv.mvcc.compaction;

import io.tieringkv.mvcc.MvccGcManager;
import io.tieringkv.mvcc.MvccMetricsRegistry;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SafePoint;
import io.tieringkv.mvcc.gc.BatchGcExecutor;
import io.tieringkv.mvcc.gc.GcConfig;
import io.tieringkv.mvcc.index.PersistentMvccIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MVCC 在线压缩（ADR-0085）：按 SafePoint 合并旧版本（保留每键最新），
 * 批量物理删除（不阻塞读/写/事务），并原子写出新 MVCC 索引文件。
 */
public final class MvccCompactor implements AutoCloseable {

    private final MvccStorageEngine engine;
    private final GcConfig config;
    private final Path indexFile;
    private final MvccMetricsRegistry metrics;
    private final BatchGcExecutor gc;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile SafePoint safePoint = SafePoint.NONE;

    public MvccCompactor(MvccStorageEngine engine, GcConfig config) {
        this(engine, config, null, null);
    }

    public MvccCompactor(MvccStorageEngine engine, GcConfig config,
                         Path indexFile, MvccMetricsRegistry metrics) {
        this.engine = engine;
        this.config = config;
        this.indexFile = indexFile;
        this.metrics = metrics;
        this.gc = new BatchGcExecutor(engine, config, metrics);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "mvcc-compactor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void updateSafePoint(SafePoint point) {
        this.safePoint = point;
        gc.updateSafePoint(point);
    }

    public SafePoint safePoint() {
        return safePoint;
    }

    /** 执行一轮压缩；返回 (回收版本数, 回收字节数)。 */
    public CompactionResult compact() throws IOException {
        MvccGcManager.GcResult result = gc.gc();
        if (indexFile != null) {
            writeCompactedIndex();
        }
        if (metrics != null) {
            metrics.recordCompaction(result.collectedVersions(),
                    result.collectedBytes());
        }
        return new CompactionResult(result.collectedVersions(),
                result.collectedBytes());
    }

    public void start(long intervalMillis) {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (safePoint != SafePoint.NONE) {
                        compact();
                    }
                } catch (IOException e) {
                    // 压缩失败不影响在线读写；下次重试
                }
            }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void writeCompactedIndex() throws IOException {
        Path tmp = indexFile.resolveSibling(
                indexFile.getFileName() + ".tmp");
        PersistentMvccIndex.save(tmp,
                PersistentMvccIndex.snapshot(engine));
        try {
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record CompactionResult(long collectedVersions,
                                   long collectedBytes) {
    }

    @Override
    public void close() {
        running.set(false);
        scheduler.shutdownNow();
        gc.close();
    }
}
