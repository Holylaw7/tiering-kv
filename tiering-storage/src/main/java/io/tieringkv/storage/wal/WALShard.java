package io.tieringkv.storage.wal;

import io.tieringkv.storage.WriteOp;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 单个 WAL 分片执行单元（v6.5.0 Phase 1/2/3）：
 * <ul>
 *   <li>每个分片独占独立的 {@link SegmentManager}、{@link WALWriter}、{@link ReentrantLock}
 *       与堆外 64 KB 聚合暂存缓冲 {@code stageBuffer}，彻底消除 16 分片锁竞争与微批次高频 syscall 风暴；</li>
 *   <li>{@code NO} / {@code EVERY_SEC} 零堆分配直接编码入 {@code stageBuffer}，满 64 KB、达段上限或后台 500 ms
 *       周期到来时一次性整块直写 {@code FileChannel}（系统调用次数缩减 ~200×，O_DIRECT 零写放大）；</li>
 *   <li>{@code ALWAYS} 绕过暂存缓冲，走分片内自适应自旋 + Group Commit（64 次 onSpinWait 后短退避 50 µs），
 *       同时批量写（{@link #appendBatchAndTrack}）单次合并编码写出 + 单次 fdatasync；</li>
 *   <li>内置 {@link InFlightApplyTracker} 维护无锁在线 Checkpoint 的安全低水位（Low-Watermark）。</li>
 * </ul>
 */
final class WALShard implements AutoCloseable {

    private static final int STAGE_BUF_BYTES = 64 * 1024;
    private static final int INITIAL_LARGE_BUF_BYTES = 256 * 1024;
    private static final int MAX_RETAINED_LARGE_BUF_BYTES = 2 * 1024 * 1024;
    private static final int MAX_GROUP_BATCH_ENTRIES = 64;
    private static final int MAX_GROUP_BATCH_BYTES = 256 * 1024;
    private static final int FOLLOWER_SPIN_ITERS = 64;
    private static final long FOLLOWER_PARK_NANOS = 50_000L;

    private final int shardId;
    private final WALConfig config;
    private final SegmentManager segments;
    private final WALWriter writer;
    private final ReentrantLock shardLock = new ReentrantLock();
    private final AtomicLong localSequence = new AtomicLong();
    private final ConcurrentLinkedQueue<CommitTicket> commitQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean leaderActive = new AtomicBoolean();
    private final InFlightApplyTracker inFlight = new InFlightApplyTracker();
    private final LongAdder writeBatches = new LongAdder();
    private final LongAdder writeRecords = new LongAdder();
    /** 分片独占 64 KB 堆外聚合缓冲（NO/EVERY_SEC 模式消除高频小包 pwrite64 系统调用）。 */
    private final ByteBuffer stageBuffer = ByteBuffer.allocateDirect(STAGE_BUF_BYTES).order(ByteOrder.BIG_ENDIAN);
    private volatile boolean stagePending;
    private long lastInlineSyncCheckMillis;
    private ByteBuffer largeBuffer = ByteBuffer.allocateDirect(INITIAL_LARGE_BUF_BYTES).order(ByteOrder.BIG_ENDIAN);
    private volatile boolean closed;

    WALShard(int shardId, WALConfig shardConfig) throws IOException {
        this.shardId = shardId;
        this.config = shardConfig;
        this.segments = new SegmentManager(shardConfig);
        this.writer = new WALWriter(segments, shardConfig.fsyncPolicy());
    }

    int shardId() {
        return shardId;
    }

    WALConfig.FsyncPolicy fsyncPolicy() {
        return writer.fsyncPolicy();
    }

    void setFsyncPolicy(WALConfig.FsyncPolicy policy) {
        shardLock.lock();
        try {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            try {
                flushStageBufferLocked(false);
            } catch (IOException e) {
                throw new WalWriteException("WAL stage flush failed", e);
            }
            writer.setFsyncPolicy(policy);
            if (policy == WALConfig.FsyncPolicy.ALWAYS) {
                try {
                    writer.force();
                } catch (IOException e) {
                    throw new WalWriteException("WAL force failed", e);
                }
            }
        } finally {
            shardLock.unlock();
        }
    }

    /** 兼容传统 WALEntry 追加（无 MemTable 跟踪令牌，供直接 WAL 调用方与单测使用）。 */
    void append(WALEntry entry) {
        long token = appendSingleAndTrack(
                entry.operation(), entry.timestamp(),
                entry.key(), entry.value(), entry.ttlMillis(), false);
        if (token != 0L) {
            completeApply(token);
        }
    }

    /**
     * 零分配单条追加并登记在途应用低水位：调用方完成 MemTable 应用后必须调用 {@link #completeApply(long)}。
     */
    long appendSingleAndTrack(
            WALEntry.Operation op,
            long timestamp,
            byte[] key,
            byte[] value,
            long ttlMillis,
            boolean trackInFlight) {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        if (writer.fsyncPolicy() == WALConfig.FsyncPolicy.ALWAYS) {
            return appendAlwaysGroupCommit(op, timestamp, key, value, ttlMillis, trackInFlight);
        }
        long seq = localSequence.incrementAndGet();
        int needed = WALRecord.encodedSize(key, op == WALEntry.Operation.PUT ? value : null);
        shardLock.lock();
        try {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            if (writer.fsyncPolicy() == WALConfig.FsyncPolicy.ALWAYS) {
                // 持锁期间策略可能刚被热切为 ALWAYS：安全走同步直写
                return appendImmediateLocked(op, timestamp, key, value, ttlMillis, seq, needed, trackInFlight);
            }
            return appendStagedSingleLocked(op, timestamp, key, value, ttlMillis, seq, needed, trackInFlight);
        } catch (IOException e) {
            throw new WalWriteException("WAL append failed", e);
        } finally {
            shardLock.unlock();
        }
    }

    private long appendStagedSingleLocked(
            WALEntry.Operation op,
            long timestamp,
            byte[] key,
            byte[] value,
            long ttlMillis,
            long seq,
            int needed,
            boolean trackInFlight) throws IOException {
        if (needed > stageBuffer.capacity()) {
            flushStageBufferLocked(true);
            return appendImmediateLocked(op, timestamp, key, value, ttlMillis, seq, needed, trackInFlight);
        }
        if (stageBuffer.remaining() < needed) {
            flushStageBufferLocked(true);
        }
        long logicalOff = writer.currentSize() + stageBuffer.position();
        long token = trackInFlight
                ? inFlight.register(writer.currentSequence(), logicalOff)
                : 0L;
        WALRecord.encodeInto(op, timestamp, key, value, ttlMillis, seq, stageBuffer);
        stagePending = true;
        writeBatches.increment();
        writeRecords.increment();

        boolean hitSegmentLimit = (writer.currentSize() + stageBuffer.position()) >= config.maxSegmentBytes();
        boolean needEverySecInlineForce = writer.fsyncPolicy() == WALConfig.FsyncPolicy.EVERY_SEC
                && (timestamp - lastInlineSyncCheckMillis >= 1000L);
        if (hitSegmentLimit || needEverySecInlineForce) {
            try {
                flushStageBufferLocked(true);
                if (needEverySecInlineForce) {
                    lastInlineSyncCheckMillis = timestamp;
                }
            } catch (IOException e) {
                if (token != 0L) {
                    inFlight.complete(token);
                }
                throw e;
            }
        }
        return token;
    }

    private long appendImmediateLocked(
            WALEntry.Operation op,
            long timestamp,
            byte[] key,
            byte[] value,
            long ttlMillis,
            long seq,
            int needed,
            boolean trackInFlight) throws IOException {
        flushStageBufferLocked(false);
        ByteBuffer buf = acquireLargeBuffer(needed);
        buf.clear();
        WALRecord.encodeInto(op, timestamp, key, value, ttlMillis, seq, buf);
        buf.flip();
        long token = trackInFlight
                ? inFlight.register(writer.currentSequence(), writer.currentSize())
                : 0L;
        try {
            writer.appendBuffer(buf);
            writer.rotateIfNeeded(config.maxSegmentBytes());
            writeBatches.increment();
            writeRecords.increment();
            return token;
        } catch (IOException e) {
            if (token != 0L) {
                inFlight.complete(token);
            }
            throw e;
        }
    }

    /**
     * 同分片零拷贝批量追加（v6.5.0 Phase 2 核心热路径）：
     * <ul>
     *   <li>{@code NO/EVERY_SEC}：1 次分片锁整批编码进 64 KB 堆外缓冲，满 64 KB 才触发 1 次 {@code pwrite64}；</li>
     *   <li>{@code ALWAYS}：1 次分片锁整批编码 + 1 次 {@code pwrite64} + 1 次 {@code fdatasync}。</li>
     * </ul>
     */
    long appendBatchAndTrack(List<WriteOp> ops, long nowMillis) {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        int count = ops.size();
        if (count == 0) {
            return 0L;
        }
        if (count == 1) {
            WriteOp single = ops.get(0);
            return appendSingleAndTrack(
                    single.delete() ? WALEntry.Operation.DELETE : WALEntry.Operation.PUT,
                    nowMillis, single.key(), single.value(), single.ttlMillis(), true);
        }
        long totalBytesLong = 0L;
        for (int i = 0; i < count; i++) {
            WriteOp op = ops.get(i);
            totalBytesLong += WALRecord.encodedSize(op.key(), op.delete() ? null : op.value());
        }
        int totalBytes = (int) Math.min(totalBytesLong, Integer.MAX_VALUE);

        shardLock.lock();
        try {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            WALConfig.FsyncPolicy policy = writer.fsyncPolicy();
            long baseSeq = localSequence.getAndAdd(count);

            // NO / EVERY_SEC 且整批可装入 64 KB 分片堆外缓冲：零系统调用直接暂存聚合
            if (policy != WALConfig.FsyncPolicy.ALWAYS && totalBytes <= stageBuffer.capacity()) {
                if (stageBuffer.remaining() < totalBytes) {
                    flushStageBufferLocked(true);
                }
                long logicalOff = writer.currentSize() + stageBuffer.position();
                long token = inFlight.register(writer.currentSequence(), logicalOff);
                for (int i = 0; i < count; i++) {
                    WriteOp op = ops.get(i);
                    WALRecord.encodeInto(
                            op.delete() ? WALEntry.Operation.DELETE : WALEntry.Operation.PUT,
                            nowMillis, op.key(), op.value(), op.ttlMillis(),
                            baseSeq + i + 1, stageBuffer);
                }
                stagePending = true;
                writeBatches.increment();
                writeRecords.add(count);

                boolean hitSegmentLimit = (writer.currentSize() + stageBuffer.position()) >= config.maxSegmentBytes();
                boolean needEverySecInlineForce = policy == WALConfig.FsyncPolicy.EVERY_SEC
                        && (nowMillis - lastInlineSyncCheckMillis >= 1000L);
                if (hitSegmentLimit || needEverySecInlineForce) {
                    try {
                        flushStageBufferLocked(true);
                        if (needEverySecInlineForce) {
                            lastInlineSyncCheckMillis = nowMillis;
                        }
                    } catch (IOException e) {
                        inFlight.complete(token);
                        throw new WalWriteException("WAL batch append failed", e);
                    }
                }
                return token;
            }

            // ALWAYS 或超大批次：先刷暂存，再整批直写 + 单次 fsync
            flushStageBufferLocked(false);
            ByteBuffer buf = acquireLargeBuffer(totalBytes);
            buf.clear();
            for (int i = 0; i < count; i++) {
                WriteOp op = ops.get(i);
                WALRecord.encodeInto(
                        op.delete() ? WALEntry.Operation.DELETE : WALEntry.Operation.PUT,
                        nowMillis, op.key(), op.value(), op.ttlMillis(),
                        baseSeq + i + 1, buf);
            }
            buf.flip();
            long token = inFlight.register(writer.currentSequence(), writer.currentSize());
            try {
                writer.appendBuffer(buf);
                writer.rotateIfNeeded(config.maxSegmentBytes());
                writeBatches.increment();
                writeRecords.add(count);
                return token;
            } catch (IOException e) {
                inFlight.complete(token);
                throw new WalWriteException("WAL batch append failed", e);
            }
        } catch (IOException e) {
            throw new WalWriteException("WAL batch append failed", e);
        } finally {
            shardLock.unlock();
        }
    }

    /** 将 64 KB 堆外暂存缓冲一次性直写当前段（仅在持 shardLock 时调用）。 */
    private void flushStageBufferLocked(boolean allowRotate) throws IOException {
        if (stageBuffer.position() == 0) {
            stagePending = false;
            return;
        }
        stageBuffer.flip();
        try {
            writer.appendBuffer(stageBuffer);
        } finally {
            stageBuffer.clear();
            stagePending = false;
        }
        if (allowRotate) {
            writer.rotateIfNeeded(config.maxSegmentBytes());
        }
    }

    /** ALWAYS 策略下的分片内自适应 Leader-Follower Group Commit（短自旋 + 50µs 退避）。 */
    private long appendAlwaysGroupCommit(
            WALEntry.Operation op,
            long timestamp,
            byte[] key,
            byte[] value,
            long ttlMillis,
            boolean trackInFlight) {
        long seq = localSequence.incrementAndGet();
        WALEntry stamped = new WALEntry(op, timestamp, key, value, ttlMillis, seq);
        byte[] encoded = WALRecord.encode(stamped);

        CommitTicket ticket = new CommitTicket(encoded, trackInFlight);
        commitQueue.offer(ticket);
        int spins = 0;
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
                if (spins < FOLLOWER_SPIN_ITERS) {
                    spins++;
                    Thread.onSpinWait();
                } else {
                    LockSupport.parkNanos(FOLLOWER_PARK_NANOS);
                }
            }
        }
        if (ticket.failure != null) {
            throw ticket.failure;
        }
        return ticket.applyToken;
    }

    private void drainAndCommitAsLeader() {
        List<CommitTicket> batch = new ArrayList<>(16);
        int batchBytes = 0;
        while (batch.size() < MAX_GROUP_BATCH_ENTRIES) {
            CommitTicket next = commitQueue.poll();
            if (next == null) {
                break;
            }
            batch.add(next);
            batchBytes += next.encoded.length;
            if (batchBytes >= MAX_GROUP_BATCH_BYTES) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        WalWriteException error = null;
        shardLock.lock();
        try {
            if (closed) {
                error = new WalWriteException("WAL is closed", null);
            } else {
                try {
                    flushStageBufferLocked(false);
                    long startSegSeq = writer.currentSequence();
                    long startOffset = writer.currentSize();
                    ByteBuffer buf = acquireLargeBuffer(batchBytes);
                    buf.clear();
                    for (int i = 0; i < batch.size(); i++) {
                        CommitTicket t = batch.get(i);
                        if (t.trackInFlight) {
                            t.applyToken = inFlight.register(startSegSeq, startOffset);
                        }
                        buf.put(t.encoded);
                    }
                    buf.flip();
                    writer.appendBuffer(buf);
                    writer.rotateIfNeeded(config.maxSegmentBytes());
                    writeBatches.increment();
                    writeRecords.add(batch.size());
                } catch (IOException e) {
                    for (int i = 0; i < batch.size(); i++) {
                        long tok = batch.get(i).applyToken;
                        if (tok != 0L) {
                            inFlight.complete(tok);
                            batch.get(i).applyToken = 0L;
                        }
                    }
                    error = new WalWriteException("WAL append failed", e);
                }
            }
        } finally {
            shardLock.unlock();
        }
        for (int i = 0; i < batch.size(); i++) {
            CommitTicket t = batch.get(i);
            t.failure = error;
            t.done = true;
            LockSupport.unpark(t.waiter);
        }
    }

    void completeApply(long token) {
        if (token != 0L) {
            inFlight.complete(token);
        }
    }

    /** 后台 EVERY_SEC 定时刷盘：先冲刷 64 KB 堆外暂存，再 fdatasync；单分片独立锁，互不阻塞。 */
    void flushQuietly() {
        if (closed || writer.fsyncPolicy() != WALConfig.FsyncPolicy.EVERY_SEC) {
            return;
        }
        if (!stagePending && !writer.hasPendingWrites()) {
            return;
        }
        shardLock.lock();
        try {
            if (closed || writer.fsyncPolicy() != WALConfig.FsyncPolicy.EVERY_SEC) {
                return;
            }
            flushStageBufferLocked(true);
            writer.force();
            lastInlineSyncCheckMillis = System.currentTimeMillis();
        } catch (IOException ignored) {
            // 下次 append/调度继续重试
        } finally {
            shardLock.unlock();
        }
    }

    void flushAndForce() {
        shardLock.lock();
        try {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            try {
                flushStageBufferLocked(true);
                writer.force();
            } catch (IOException e) {
                throw new WalWriteException("WAL force failed", e);
            }
        } finally {
            shardLock.unlock();
        }
    }

    void rotate() {
        shardLock.lock();
        try {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            try {
                flushStageBufferLocked(false);
                writer.rotateIfNeeded(0);
            } catch (IOException e) {
                throw new WalWriteException("WAL rotate failed", e);
            }
        } finally {
            shardLock.unlock();
        }
    }

    WALManager.WALPosition position() {
        shardLock.lock();
        try {
            return new WALManager.WALPosition(writer.currentSequence(), writer.currentSize() + stageBuffer.position());
        } finally {
            shardLock.unlock();
        }
    }

    /**
     * 在线 Checkpoint 安全低水位（零全局 STW）：
     * 先把本分片暂存区落入文件（保证位点不超过文件真实长度），再返回在途未 apply 写的最小起始位点。
     */
    WALManager.WALPosition safeCheckpointPosition() {
        shardLock.lock();
        try {
            if (!closed && stageBuffer.position() > 0) {
                try {
                    flushStageBufferLocked(true);
                } catch (IOException e) {
                    throw new WalWriteException("WAL checkpoint stage flush failed", e);
                }
            }
            long curSeq = writer.currentSequence();
            long curOff = writer.currentSize();
            return inFlight.lowWatermark(curSeq, curOff);
        } finally {
            shardLock.unlock();
        }
    }

    long writeBatches() {
        return writeBatches.sum();
    }

    long writeRecords() {
        return writeRecords.sum();
    }

    long bytesWritten() {
        return writer.bytesWritten() + (stagePending ? stageBuffer.position() : 0L);
    }

    long fsyncCount() {
        return writer.fsyncCount();
    }

    long fsyncTotalNanos() {
        return writer.fsyncTotalNanos();
    }

    long fsyncMaxNanos() {
        return writer.fsyncMaxNanos();
    }

    String activeIoBackend() {
        return segments.activeIoBackend();
    }

    boolean isPreallocated() {
        return segments.isPreallocated();
    }

    private ByteBuffer acquireLargeBuffer(int needed) {
        if (largeBuffer.capacity() >= needed) {
            return largeBuffer;
        }
        int target = Math.max(needed, largeBuffer.capacity() * 2);
        ByteBuffer grown = ByteBuffer.allocateDirect(target).order(ByteOrder.BIG_ENDIAN);
        if (target <= MAX_RETAINED_LARGE_BUF_BYTES) {
            largeBuffer = grown;
        }
        return grown;
    }

    @Override
    public void close() throws IOException {
        shardLock.lock();
        try {
            IOException flushErr = null;
            if (!closed && stageBuffer.position() > 0) {
                try {
                    flushStageBufferLocked(false);
                } catch (IOException e) {
                    flushErr = e;
                }
            }
            closed = true;
            try {
                writer.close();
            } catch (IOException e) {
                if (flushErr == null) {
                    throw e;
                }
                flushErr.addSuppressed(e);
                throw flushErr;
            }
            if (flushErr != null) {
                throw flushErr;
            }
        } finally {
            shardLock.unlock();
        }
    }

    private static final class CommitTicket {
        final byte[] encoded;
        final boolean trackInFlight;
        final Thread waiter;
        volatile boolean done;
        volatile WalWriteException failure;
        long applyToken;

        CommitTicket(byte[] encoded, boolean trackInFlight) {
            this.encoded = encoded;
            this.trackInFlight = trackInFlight;
            this.waiter = Thread.currentThread();
        }
    }

    /**
     * 分片内轻量在途应用水位追踪器（严格位于锁层级最末端：keyLock → shardLock → inFlightLock）。
     * 采用紧凑原始数组（常规活跃数 0–2），零对象分配。
     */
    private static final class InFlightApplyTracker {
        private long nextToken = 1L;
        private long[] tokens = new long[8];
        private long[] seqs = new long[8];
        private long[] offsets = new long[8];
        private int count;

        synchronized long register(long seq, long offset) {
            long token = nextToken++;
            if (token == 0L) {
                token = nextToken++;
            }
            if (count == tokens.length) {
                int newCap = tokens.length * 2;
                tokens = java.util.Arrays.copyOf(tokens, newCap);
                seqs = java.util.Arrays.copyOf(seqs, newCap);
                offsets = java.util.Arrays.copyOf(offsets, newCap);
            }
            tokens[count] = token;
            seqs[count] = seq;
            offsets[count] = offset;
            count++;
            return token;
        }

        synchronized void complete(long token) {
            for (int i = count - 1; i >= 0; i--) {
                if (tokens[i] == token) {
                    int last = --count;
                    if (i != last) {
                        tokens[i] = tokens[last];
                        seqs[i] = seqs[last];
                        offsets[i] = offsets[last];
                    }
                    tokens[last] = 0L;
                    return;
                }
            }
        }

        synchronized WALManager.WALPosition lowWatermark(long currentSeq, long currentOffset) {
            if (count == 0) {
                return new WALManager.WALPosition(currentSeq, currentOffset);
            }
            long minSeq = currentSeq;
            long minOff = currentOffset;
            for (int i = 0; i < count; i++) {
                long s = seqs[i];
                long o = offsets[i];
                if (s < minSeq || (s == minSeq && o < minOff)) {
                    minSeq = s;
                    minOff = o;
                }
            }
            return new WALManager.WALPosition(minSeq, minOff);
        }
    }
}
