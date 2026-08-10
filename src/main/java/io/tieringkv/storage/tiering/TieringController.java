package io.tieringkv.storage.tiering;

import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.cold.SSTableMeta;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALManager;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 分层调度控制器（ADR-0020）：统一管理水位、异步 Flush、异步迁移、
 * 背压与指标。
 */
public final class TieringController implements AutoCloseable {

    public record Config(
            WatermarkManager.Config watermarks,
            int flushWorkers,
            int migrationWorkers,
            int maxMigrationRetries,
            long retryBackoffMillis,
            long backpressureTimeoutMillis,
            Path migrationDirectory) {

        public static Config defaults(Path migrationDirectory) {
            return new Config(WatermarkManager.Config.defaults(), 1, 2, 3, 0, 1000,
                    migrationDirectory);
        }
    }

    private final Config config;
    private final MemoryManager memory;
    private final MemTable memTable;
    private final WALManager wal;
    private final ColdStorageEngine cold;
    private final TierWorkerPool flushPool;
    private final TierWorkerPool migrationPool;
    private final StorageMetrics metrics = new StorageMetrics();
    private final WatermarkManager watermark;
    private final FlushScheduler flushScheduler;
    private final MigrationScheduler migrationScheduler;
    private final BackPressureController backpressure;

    public TieringController(
            Config config,
            MemoryManager memory,
            MemTable memTable,
            WALManager wal,
            ColdStorageEngine cold) throws IOException {
        this.config = config;
        this.memory = memory;
        this.memTable = memTable;
        this.wal = wal;
        this.cold = cold;
        this.flushPool = new TierWorkerPool(config.flushWorkers(), "tiering-flush");
        this.migrationPool = new TierWorkerPool(config.migrationWorkers(), "tiering-migration");
        this.watermark = new WatermarkManager(config.watermarks());
        this.flushScheduler = new FlushScheduler(flushPool, memTable, wal, cold, metrics);
        this.migrationScheduler = new MigrationScheduler(
                migrationPool,
                new MigrationLog(config.migrationDirectory()),
                cold, wal, memTable, metrics,
                config.maxMigrationRetries(), config.retryBackoffMillis());
        this.backpressure = new BackPressureController(
                watermark, memory, memTable::size, metrics::pendingMigrationCount);
    }

    /** 启动恢复：重放未完成迁移；水位高时立即调度 Flush。 */
    public void recover() throws IOException {
        migrationScheduler.recoverAndResume();
        if (watermark.isFlushNeeded(memory.usedBytes(), memory.maxBytes(), memTable.size())) {
            flushScheduler.scheduleFlush();
        }
    }

    /** 写路径完成回调：刷新指标并检查水位（触发异步 Flush）。 */
    public void onWriteCompleted() {
        metrics.setMemoryGauges(memory.usedBytes(), memory.maxBytes(), memTable.size());
        long sstableCount = cold.tablesSnapshot().size();
        long diskUsage = cold.tablesSnapshot().stream().mapToLong(SSTableMeta::fileSize).sum();
        metrics.setColdGauges(sstableCount, diskUsage);
        if (watermark.isFlushNeeded(memory.usedBytes(), memory.maxBytes(), memTable.size())) {
            flushScheduler.scheduleFlush();
        }
    }

    /** 写路径入口：CRITICAL 时有界等待，超时返回 false。 */
    public boolean awaitWritable() {
        return backpressure.awaitWritable(config.backpressureTimeoutMillis());
    }

    public TierState currentState() {
        return backpressure.currentState();
    }

    public StorageMetrics metrics() {
        return metrics;
    }

    public FlushScheduler flushScheduler() {
        return flushScheduler;
    }

    public MigrationScheduler migrationScheduler() {
        return migrationScheduler;
    }

    public TierWorkerPool flushPool() {
        return flushPool;
    }

    public TierWorkerPool migrationPool() {
        return migrationPool;
    }

    @Override
    public void close() throws IOException {
        migrationScheduler.close();
        flushPool.close();
        migrationPool.close();
    }
}
