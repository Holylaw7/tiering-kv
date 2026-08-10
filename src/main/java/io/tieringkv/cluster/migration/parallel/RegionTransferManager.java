package io.tieringkv.cluster.migration.parallel;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 并行迁移管理器（ADR-0063）：transfer.worker.pool = min(8, CPU)；
 * MemTable 按段分片并行（独立快照），通用存储回退单分片；
 * chunk 级 checkpoint + CRC + retry + pause/resume。
 */
public final class RegionTransferManager implements AutoCloseable {

    private final StorageEngine source;
    private final StorageEngine target;
    private final Path cursorDir;
    private final long versionBarrier;
    private final int workerCount;
    private final long rateLimitBytesPerSec;
    private final ChunkWorker.PauseController pauseController =
            new ChunkWorker.PauseController();

    public RegionTransferManager(StorageEngine source, StorageEngine target,
                                 Path cursorDir, int workerCount,
                                 long versionBarrier) {
        this(source, target, cursorDir, workerCount, versionBarrier, 0);
    }

    public RegionTransferManager(StorageEngine source, StorageEngine target,
                                 Path cursorDir, int workerCount,
                                 long versionBarrier,
                                 long rateLimitBytesPerSec) {
        this.source = source;
        this.target = target;
        this.cursorDir = cursorDir;
        this.workerCount = Math.max(1, workerCount);
        this.versionBarrier = versionBarrier;
        this.rateLimitBytesPerSec = rateLimitBytesPerSec;
    }

    public static int defaultWorkerCount() {
        return Math.min(8, Runtime.getRuntime().availableProcessors());
    }

    public int workerCount() {
        return workerCount;
    }

    /** 并行迁移；chunkCount 必须 >= 1（MemTable 时按段分片）。 */
    public MigrationSummary migrate(int chunkCount) throws Exception {
        if (chunkCount < 1) {
            throw new IllegalArgumentException("chunkCount must be positive");
        }
        List<MigrationChunk> chunks = buildChunks(chunkCount);
        AtomicLong entries = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        ByteRateLimiter rateLimiter = new ByteRateLimiter(rateLimitBytesPerSec);
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(workerCount, chunks.size()));
        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (MigrationChunk chunk : chunks) {
                futures.add(pool.submit(new ChunkWorker(
                        source, target, cursorDir, versionBarrier,
                        chunk, pauseController, entries, bytes, rateLimiter)));
            }
            for (Future<?> future : futures) {
                future.get(300, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        long durationNanos = System.nanoTime() - start;
        int failed = 0;
        for (MigrationChunk chunk : chunks) {
            if (ChunkCheckpoint.load(cursorDir, chunk.chunkId()).status()
                    == ChunkCheckpoint.Status.FAILED) {
                failed++;
            }
        }
        return new MigrationSummary(
                chunks.size(), chunks.size() - failed, failed,
                entries.get(), bytes.get(), durationNanos);
    }

    public void pause() {
        pauseController.pause();
    }

    public void resume() {
        pauseController.resume();
    }

    private List<MigrationChunk> buildChunks(int chunkCount) {
        List<MigrationChunk> chunks = new ArrayList<>();
        if (source instanceof MemTable) {
            int perChunk = (MemTable.SEGMENT_COUNT + chunkCount - 1) / chunkCount;
            int from = 0;
            int id = 0;
            while (from < MemTable.SEGMENT_COUNT) {
                int to = Math.min(MemTable.SEGMENT_COUNT - 1, from + perChunk - 1);
                chunks.add(new MigrationChunk(id++, from, to,
                        new byte[0], null, versionBarrier));
                from = to + 1;
            }
        } else {
            chunks.add(new MigrationChunk(0, -1, -1,
                    new byte[0], null, versionBarrier));
        }
        return chunks;
    }

    public static final class MigrationSummary {
        private final int chunkCount;
        private final int doneChunks;
        private final int failedChunks;
        private final long entries;
        private final long bytes;
        private final long durationNanos;

        MigrationSummary(int chunkCount, int doneChunks, int failedChunks,
                         long entries, long bytes, long durationNanos) {
            this.chunkCount = chunkCount;
            this.doneChunks = doneChunks;
            this.failedChunks = failedChunks;
            this.entries = entries;
            this.bytes = bytes;
            this.durationNanos = durationNanos;
        }

        public int chunkCount() {
            return chunkCount;
        }

        public int doneChunks() {
            return doneChunks;
        }

        public int failedChunks() {
            return failedChunks;
        }

        public long entries() {
            return entries;
        }

        public long bytes() {
            return bytes;
        }

        public long durationNanos() {
            return durationNanos;
        }

        public double mbPerSec() {
            double seconds = durationNanos / 1_000_000_000.0;
            return seconds <= 0 ? 0 : bytes / 1024.0 / 1024.0 / seconds;
        }
    }

    @Override
    public void close() {
        // 无持有资源；保留接口以对齐生命周期
    }
}
