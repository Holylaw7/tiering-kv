package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WAL 管理器（ADR-0014/0016）：append / flush / rotate / recover / checkpoint。
 * append 在锁内串行化（当前写路径为事件循环，多连接并发由此处兜底）。
 */
public final class WALManager implements AutoCloseable {

    private final WALConfig config;
    private final SegmentManager segments;
    private final WALWriter writer;
    private final AtomicLong recordSequence = new AtomicLong();
    private final Object writeLock = new Object();
    private boolean closed;
    /** EVERY_SEC 的后台落盘器（TD-103）：空闲时也必须把 buffer 刷出去。 */
    private final java.util.concurrent.ScheduledExecutorService flusher;

    public WALManager(WALConfig config) throws IOException {
        this.config = config;
        this.segments = new SegmentManager(config.directory());
        this.writer = new WALWriter(segments, config.fsyncPolicy());
        if (config.fsyncPolicy() == WALConfig.FsyncPolicy.EVERY_SEC) {
            // 仅靠 append 时判断"距上次 force ≥1s"会让空闲期写入一直留在
            // BufferedOutputStream 里：崩溃即丢失（Phase 10 实测 99/100 丢失）。
            this.flusher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "wal-fsync");
                        thread.setDaemon(true);
                        return thread;
                    });
            this.flusher.scheduleWithFixedDelay(this::flushQuietly, 500, 500,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
        } else {
            this.flusher = null;
        }
    }

    /** 后台定时落盘：失败只记录，不打断写路径（下次 append 仍会重试）。 */
    private void flushQuietly() {
        synchronized (writeLock) {
            if (closed) {
                return;
            }
            try {
                writer.force();
            } catch (IOException ignored) {
                // 由后续 append/force 重试；此处不抛出，避免线程退出
            }
        }
    }

    /** 追加记录（写失败抛 {@link WalWriteException}，不进入 MemTable）。 */
    public void append(WALEntry entry) {
        synchronized (writeLock) {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            try {
                // 保留调用方时间戳：恢复时按 timestamp + ttl 计算绝对过期点
                WALEntry stamped = new WALEntry(entry.operation(), entry.timestamp(),
                        entry.key(), entry.value(), entry.ttlMillis(),
                        recordSequence.incrementAndGet());
                writer.append(stamped);
                writer.rotateIfNeeded(config.maxSegmentBytes());
            } catch (IOException e) {
                throw new WalWriteException("WAL append failed", e);
            }
        }
    }

    public void flushAndForce() {
        synchronized (writeLock) {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            try {
                writer.force();
            } catch (IOException e) {
                throw new WalWriteException("WAL force failed", e);
            }
        }
    }

    public void rotate() {
        synchronized (writeLock) {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            try {
                writer.rotateIfNeeded(0);
            } catch (IOException e) {
                throw new WalWriteException("WAL rotate failed", e);
            }
        }
    }

    /** 当前写位置（segment 序号 + 字节偏移），供 checkpoint 使用。 */
    public WALPosition position() {
        synchronized (writeLock) {
            return new WALPosition(writer.currentSequence(), writer.currentSize());
        }
    }

    public RecoveryManager.RecoveryStats recover(MemTable memTable) throws IOException {
        return new RecoveryManager(config).recover(memTable);
    }

    /** 从指定位置之后恢复（checkpoint 场景）。 */
    public RecoveryManager.RecoveryStats recoverFrom(
            MemTable memTable, long startSequence, long startOffset) throws IOException {
        return new RecoveryManager(config).recoverFrom(memTable, startSequence, startOffset);
    }

    /** 先捕获 offset、后快照、再落盘（ADR-0016 防竞态顺序）。 */
    public void checkpoint(MemTable memTable) throws IOException {
        synchronized (writeLock) {
            WALPosition position = new WALPosition(writer.currentSequence(), writer.currentSize());
            List<KeyValueEntry> entries = new ArrayList<>();
            try (var iterator = memTable.iterator()) {
                while (iterator.hasNext()) {
                    entries.add(iterator.next());
                }
            }
            CheckpointManager.write(config.directory(), position, entries);
        }
    }

    public CheckpointManager.Checkpoint loadCheckpoint() {
        return CheckpointManager.read(config.directory());
    }

    @Override
    public void close() throws IOException {
        if (flusher != null) {
            flusher.shutdownNow();
        }
        synchronized (writeLock) {
            writer.close();
            closed = true;
        }
    }

    public record WALPosition(long sequence, long offset) {
    }
}