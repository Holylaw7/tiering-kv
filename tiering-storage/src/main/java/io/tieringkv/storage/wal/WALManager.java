package io.tieringkv.storage.wal;

import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * WAL 管理器（ADR-0014/0016 / B3 Group Commit 优化）：append / flush / rotate / recover / checkpoint。
 *
 * <p>B3 优化：记录序列化与 CRC32C 校验全部移出 {@code writeLock} 并行执行；
 * 并发写入通过 Leader-Follower Group Commit 队列合并为单次段批量写与单次 fsync，
 * 在 {@code fsync=ALWAYS} 下保持严格崩溃持久性等价的同时大幅提升并发写吞吐。
 */
public final class WALManager implements AutoCloseable {

    private static final int MAX_GROUP_BATCH_ENTRIES = 64;
    private static final int MAX_GROUP_BATCH_BYTES = 256 * 1024;
    private static final long FOLLOWER_PARK_NANOS = 1_000_000L;

    private final WALConfig config;
    private final SegmentManager segments;
    private final WALWriter writer;
    private final AtomicLong recordSequence = new AtomicLong();
    private final Object writeLock = new Object();
    private final Object checkpointLock = new Object();
    private final Object[] keyLocks = createKeyLocks();
    private final ConcurrentLinkedQueue<CommitTicket> commitQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean leaderActive = new AtomicBoolean();
    private final LongAdder groupCommitBatches = new LongAdder();
    private final LongAdder groupCommitRecords = new LongAdder();
    private volatile boolean closed;
    /** EVERY_SEC 的后台落盘器（TD-103）：空闲时也必须把 buffer 刷出去。 */
    private java.util.concurrent.ScheduledExecutorService flusher;

    public WALManager(WALConfig config) throws IOException {
        this.config = config;
        this.segments = new SegmentManager(config.directory());
        this.writer = new WALWriter(segments, config.fsyncPolicy());
        if (config.fsyncPolicy() == WALConfig.FsyncPolicy.EVERY_SEC) {
            ensureFlusherStarted();
        }
    }

    private void ensureFlusherStarted() {
        if (flusher != null) {
            return;
        }
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
    }

    public WALConfig.FsyncPolicy fsyncPolicy() {
        synchronized (writeLock) {
            return writer.fsyncPolicy();
        }
    }

    /** 运行期切换 WAL fsync 策略（供 CONFIG SET appendfsync 接线）。 */
    public void setFsyncPolicy(WALConfig.FsyncPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy required");
        }
        synchronized (writeLock) {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            writer.setFsyncPolicy(policy);
            if (policy == WALConfig.FsyncPolicy.EVERY_SEC) {
                ensureFlusherStarted();
            } else if (policy == WALConfig.FsyncPolicy.ALWAYS) {
                try {
                    writer.force();
                } catch (IOException e) {
                    throw new WalWriteException("WAL force failed", e);
                }
            }
        }
    }

    /** 后台定时落盘：失败只记录，不打断写路径（下次 append 仍会重试）。 */
    private void flushQuietly() {
        synchronized (writeLock) {
            if (closed || writer.fsyncPolicy() != WALConfig.FsyncPolicy.EVERY_SEC) {
                return;
            }
            try {
                writer.force();
            } catch (IOException ignored) {
                // 由后续 append/force 重试；此处不抛出，避免线程退出
            }
        }
    }

    /**
     * 追加记录（写失败抛 {@link WalWriteException}，不进入 MemTable）：
     * 1) 锁外并发完成 WALRecord 编码与 CRC32C；
     * 2) 入队 Leader-Follower Group Commit 队列，单批单次 fsync 落盘。
     */
    public void append(WALEntry entry) {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        // 保留调用方时间戳：恢复时按 timestamp + ttl 计算绝对过期点；编码与 CRC32C 在锁外并发执行
        WALEntry stamped = new WALEntry(entry.operation(), entry.timestamp(),
                entry.key(), entry.value(), entry.ttlMillis(),
                recordSequence.incrementAndGet());
        byte[] encoded = WALRecord.encode(stamped);

        CommitTicket ticket = new CommitTicket(encoded);
        commitQueue.offer(ticket);
        while (!ticket.done) {
            if (leaderActive.compareAndSet(false, true)) {
                try {
                    drainAndCommitAsLeader();
                } finally {
                    leaderActive.set(false);
                    CommitTicket next = commitQueue.peek();
                    if (next != null) {
                        LockSupport.unpark(next.waiter);
                    }
                }
            } else if (!ticket.done) {
                LockSupport.parkNanos(FOLLOWER_PARK_NANOS);
            }
        }
        if (ticket.failure != null) {
            throw ticket.failure;
        }
    }

    private void drainAndCommitAsLeader() {
        List<CommitTicket> batch = new ArrayList<>(16);
        List<byte[]> frames = new ArrayList<>(16);
        int batchBytes = 0;
        while (batch.size() < MAX_GROUP_BATCH_ENTRIES) {
            CommitTicket next = commitQueue.poll();
            if (next == null) {
                break;
            }
            batch.add(next);
            frames.add(next.encoded);
            batchBytes += next.encoded.length;
            if (batchBytes >= MAX_GROUP_BATCH_BYTES) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        WalWriteException error = null;
        synchronized (writeLock) {
            if (closed) {
                error = new WalWriteException("WAL is closed", null);
            } else {
                try {
                    writer.appendEncodedBatch(frames);
                    writer.rotateIfNeeded(config.maxSegmentBytes());
                    groupCommitBatches.increment();
                    groupCommitRecords.add(batch.size());
                } catch (IOException e) {
                    error = new WalWriteException("WAL append failed", e);
                }
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            CommitTicket t = batch.get(i);
            t.failure = error;
            t.done = true;
            LockSupport.unpark(t.waiter);
        }
    }

    /** Group Commit 累计提交批次数（用于观测与基准验证）。 */
    public long groupCommitBatches() {
        return groupCommitBatches.sum();
    }

    /** Group Commit 累计提交记录条数（用于观测与基准验证）。 */
    public long groupCommitRecords() {
        return groupCommitRecords.sum();
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

    /** 先捕获 offset、后快照、再落盘（ADR-0016 防竞态顺序）；快照遍历不持 writeLock，避免锁顺序反转。 */
    public void checkpoint(MemTable memTable) throws IOException {
        synchronized (checkpointLock) {
            WALPosition position;
            synchronized (writeLock) {
                position = new WALPosition(writer.currentSequence(), writer.currentSize());
            }
            List<KeyValueEntry> entries = new ArrayList<>();
            try (var iterator = memTable.iterator()) {
                while (iterator.hasNext()) {
                    entries.add(iterator.next());
                }
            }
            CheckpointManager.write(config.directory(), position, entries);
        }
    }

    /** 按键分片互斥（256 桶），用于协调热写路径与后台淘汰/迁移的 WAL 顺序。 */
    public <T> T withKeyLock(byte[] key, java.util.function.Supplier<T> action) {
        synchronized (stripeFor(key)) {
            return action.get();
        }
    }

    public void withKeyLock(byte[] key, Runnable action) {
        synchronized (stripeFor(key)) {
            action.run();
        }
    }

    private Object stripeFor(byte[] key) {
        int hash = 0x811c9dc5;
        if (key != null) {
            for (byte b : key) {
                hash ^= (b & 0xff);
                hash *= 0x01000193;
            }
        }
        return keyLocks[hash & 255];
    }

    private static Object[] createKeyLocks() {
        Object[] locks = new Object[256];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    public CheckpointManager.Checkpoint loadCheckpoint() {
        return CheckpointManager.read(config.directory());
    }

    @Override
    public void close() throws IOException {
        java.util.concurrent.ScheduledExecutorService executorToStop;
        synchronized (writeLock) {
            closed = true;
            executorToStop = flusher;
            writer.close();
        }
        if (executorToStop != null) {
            executorToStop.shutdownNow();
        }
    }

    public record WALPosition(long sequence, long offset) {
    }

    private static final class CommitTicket {
        final byte[] encoded;
        final Thread waiter;
        volatile boolean done;
        volatile WalWriteException failure;

        CommitTicket(byte[] encoded) {
            this.encoded = encoded;
            this.waiter = Thread.currentThread();
        }
    }
}
