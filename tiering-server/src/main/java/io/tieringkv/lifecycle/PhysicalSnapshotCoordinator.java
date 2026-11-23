package io.tieringkv.lifecycle;

import io.tieringkv.observability.VectorMetricsRegistry;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 物理快照协调器（N1-B）：通过实例内互斥执行 WAL Checkpoint、冷层 SSTable 刷盘与向量索引落盘，
 * 为 BGSAVE / LASTSAVE 与 INFO # Persistence 提供单一权威状态源。
 */
public final class PhysicalSnapshotCoordinator implements AutoCloseable {

    @FunctionalInterface
    public interface SnapshotAction {
        void execute() throws IOException;
    }

    public record StatusSnapshot(
            boolean inProgress,
            long lastSaveEpochSeconds,
            String lastStatus,
            String lastError,
            long lastDurationMillis,
            long totalSuccess) {
    }

    private final SnapshotAction action;
    private final ExecutorService executor;
    private final AtomicBoolean inProgress = new AtomicBoolean(false);
    private final AtomicLong totalSuccess = new AtomicLong(0);
    private volatile StatusSnapshot state = new StatusSnapshot(false, 0L, "ok", null, 0L, 0L);
    private volatile boolean closed = false;

    public PhysicalSnapshotCoordinator(WALManager wal, MemTable memTable, ColdStorageEngine cold) {
        this(wal, memTable, cold, null, null, null);
    }

    public PhysicalSnapshotCoordinator(
            WALManager wal,
            MemTable memTable,
            ColdStorageEngine cold,
            VectorIndexStore vectorStore,
            Path vectorCheckpointFile,
            VectorMetricsRegistry vectorMetrics) {
        this(buildDefaultAction(wal, memTable, cold, vectorStore, vectorCheckpointFile, vectorMetrics));
    }

    public PhysicalSnapshotCoordinator(SnapshotAction action) {
        this.action = Objects.requireNonNull(action, "action");
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tiering-bgsave");
            t.setDaemon(true);
            return t;
        });
    }

    private static SnapshotAction buildDefaultAction(
            WALManager wal,
            MemTable memTable,
            ColdStorageEngine cold,
            VectorIndexStore vectorStore,
            Path vectorCheckpointFile,
            VectorMetricsRegistry vectorMetrics) {
        return () -> {
            if (wal != null && memTable != null) {
                wal.flushAndForce();
                wal.checkpoint(memTable);
            }
            if (cold != null) {
                cold.flushPending();
            }
            if (vectorStore != null && vectorCheckpointFile != null
                    && (vectorStore.size() > 0 || Files.exists(vectorCheckpointFile))) {
                Path parent = vectorCheckpointFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                vectorStore.checkpoint(vectorCheckpointFile);
                if (vectorMetrics != null) {
                    vectorMetrics.recordVectorCheckpoint(vectorStore.size());
                }
            }
        };
    }

    /**
     * 异步触发物理快照；若已有任务正在执行或已关闭返回 {@code false}。
     */
    public boolean triggerAsync() {
        if (closed || !inProgress.compareAndSet(false, true)) {
            return false;
        }
        try {
            executor.submit(this::runTask);
            return true;
        } catch (RuntimeException ex) {
            inProgress.set(false);
            throw ex;
        }
    }

    /** 同步执行一次物理快照（单测或工具路径可用），受同一互斥锁保护。 */
    public boolean runSync() throws IOException {
        if (closed || !inProgress.compareAndSet(false, true)) {
            return false;
        }
        long startNanos = System.nanoTime();
        try {
            action.execute();
            recordSuccess(startNanos);
            return true;
        } catch (IOException | RuntimeException ex) {
            recordFailure(startNanos, ex);
            throw ex;
        } finally {
            inProgress.set(false);
        }
    }

    private void runTask() {
        long startNanos = System.nanoTime();
        try {
            action.execute();
            recordSuccess(startNanos);
        } catch (Exception ex) {
            recordFailure(startNanos, ex);
        } finally {
            inProgress.set(false);
        }
    }

    private void recordSuccess(long startNanos) {
        long durationMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long count = totalSuccess.incrementAndGet();
        state = new StatusSnapshot(false, nowSeconds, "ok", null, durationMillis, count);
    }

    private void recordFailure(long startNanos, Throwable ex) {
        long durationMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        StatusSnapshot prev = state;
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        state = new StatusSnapshot(false, prev.lastSaveEpochSeconds(), "err", msg,
                durationMillis, prev.totalSuccess());
    }

    public StatusSnapshot status() {
        StatusSnapshot current = state;
        boolean running = inProgress.get();
        return running == current.inProgress()
                ? current
                : new StatusSnapshot(running, current.lastSaveEpochSeconds(), current.lastStatus(),
                        current.lastError(), current.lastDurationMillis(), current.totalSuccess());
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
    }
}
