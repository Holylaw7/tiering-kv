package io.tieringkv.storage.wal;

import io.tieringkv.storage.WriteOp;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * WAL 管理器（ADR-0014/0016 + v6.5.0 Sharded WAL 路由层）：
 *
 * <p>v6.5.0 架构演进：
 * <ul>
 *   <li>当 {@code config.shardCount() == 1} 时保持原有单目录 {@code 000001.log} 行为，100% 兼容既有单测；</li>
 *   <li>当 {@code config.shardCount() > 1}（生产默认 16）时拆分为 16 路独立 {@link WALShard}，
 *       采用与 {@code ShardRouter} 一致的 FNV-1a 路由，彻底消除跨分片写锁竞争与 {@code futex} 唤醒风暴；</li>
 *   <li>256 桶 {@link ReentrantLock} 键锁支持批量按升序条带无死锁加锁，确保与后台淘汰/迁移严格有序；</li>
 *   <li>在线 {@link #checkpoint(MemTable)} 基于各分片无锁安全低水位向量（{@code VERSION=2}），零 STW 停顿；</li>
 *   <li>恢复期间安全关闭并重开分片句柄，彻底避免跨平台（Windows/Linux）文件截断、旧版迁移与重分片句柄冲突。</li>
 * </ul>
 */
public final class WALManager implements AutoCloseable {

    static final String CLEAR_EPOCH_FILE = "clear.epoch";

    private final WALConfig config;
    private final int shardCount;
    private final Object shardsLifecycleLock = new Object();
    private volatile WALShard[] shards;
    /** L1 后台分层任务屏障：FlushManager / MigrationScheduler.runBatch 持读锁，clear() 持写锁；热写路径永不触碰。 */
    private final ReentrantReadWriteLock backgroundTierLock = new ReentrantReadWriteLock();
    private final Object checkpointLock = new Object();
    private final ReentrantLock[] keyLocks = createKeyLocks();
    private final Object flusherLock = new Object();
    private final AtomicLong committedClearEpoch = new AtomicLong(0L);
    private final AtomicLong nextClearEpochFloor = new AtomicLong(0L);
    private volatile WALConfig.FsyncPolicy activeFsyncPolicy;
    private volatile boolean closed;
    /** EVERY_SEC 的后台落盘器（TD-103）：逐分片独立刷盘，互不阻塞。 */
    private ScheduledExecutorService flusher;

    public WALManager(WALConfig config) throws IOException {
        this.config = config;
        this.shardCount = config.shardCount();
        this.activeFsyncPolicy = config.fsyncPolicy();
        Files.createDirectories(config.directory());
        long persistedEpoch = readClearEpochFile(config.directory());
        if (persistedEpoch > 0L) {
            committedClearEpoch.set(persistedEpoch);
            nextClearEpochFloor.set(persistedEpoch);
        }
        if (!hasPendingMigrationOrReshard()) {
            openShardsLocked();
        }
        if (config.fsyncPolicy() == WALConfig.FsyncPolicy.EVERY_SEC) {
            ensureFlusherStarted();
        }
    }

    private boolean hasPendingMigrationOrReshard() throws IOException {
        List<java.nio.file.Path> shardDirs = ShardedRecoveryManager.listShardDirectories(config.directory());
        List<Long> rootLogs = RecoveryManager.listSegments(config.directory());
        if (shardCount > 1 && shardDirs.isEmpty() && !rootLogs.isEmpty()) {
            return true; // 旧版单 WAL 待平滑升级为分片 WAL
        }
        if (!shardDirs.isEmpty()) {
            int metaShards = ShardedRecoveryManager.readMetaShardCount(config.directory());
            int existing = metaShards > 0 ? metaShards : shardDirs.size();
            if (existing != shardCount) {
                return true; // wal.shards 发生变更，待归并重分片
            }
        }
        return false;
    }

    private WALShard[] ensureShardsOpen() {
        WALShard[] current = this.shards;
        if (current != null) {
            return current;
        }
        synchronized (shardsLifecycleLock) {
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            if (this.shards == null) {
                try {
                    openShardsLocked();
                } catch (IOException e) {
                    throw new WalWriteException("failed to open WAL shards", e);
                }
            }
            return this.shards;
        }
    }

    private void openShardsLocked() throws IOException {
        WALShard[] created = new WALShard[shardCount];
        boolean initFailed = true;
        try {
            if (shardCount > 1) {
                ShardedRecoveryManager.writeMeta(config.directory(), shardCount);
            }
            WALConfig.FsyncPolicy policy = this.activeFsyncPolicy;
            for (int i = 0; i < shardCount; i++) {
                WALConfig shardCfg = config.forShard(i);
                if (shardCfg.fsyncPolicy() != policy) {
                    shardCfg = new WALConfig(shardCfg.directory(), shardCfg.maxSegmentBytes(),
                            policy, 1, shardCfg.ioMode(), shardCfg.preallocateSegments());
                }
                created[i] = new WALShard(i, shardCfg);
            }
            this.shards = created;
            initFailed = false;
        } finally {
            if (initFailed) {
                for (WALShard shard : created) {
                    if (shard != null) {
                        try {
                            shard.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }
    }

    private void closeShardsLocked() throws IOException {
        WALShard[] current = this.shards;
        if (current == null) {
            return;
        }
        this.shards = null;
        IOException firstError = null;
        for (WALShard shard : current) {
            if (shard != null) {
                try {
                    shard.close();
                } catch (IOException e) {
                    if (firstError == null) {
                        firstError = e;
                    } else {
                        firstError.addSuppressed(e);
                    }
                }
            }
        }
        if (firstError != null) {
            throw firstError;
        }
    }

    private void ensureFlusherStarted() {
        synchronized (flusherLock) {
            if (flusher != null) {
                return;
            }
            this.flusher = Executors.newSingleThreadScheduledExecutor(
                    runnable -> {
                        Thread thread = new Thread(runnable, "wal-fsync");
                        thread.setDaemon(true);
                        return thread;
                    });
            this.flusher.scheduleWithFixedDelay(this::flushQuietlyAllShards, 500, 500,
                    TimeUnit.MILLISECONDS);
        }
    }

    public int shardCount() {
        return shardCount;
    }

    public int routeShard(byte[] key) {
        return WalShardRouter.route(key, shardCount);
    }

    public WALConfig.FsyncPolicy fsyncPolicy() {
        return activeFsyncPolicy;
    }

    /** 运行期切换 WAL fsync 策略（供 CONFIG SET appendfsync 接线）。 */
    public void setFsyncPolicy(WALConfig.FsyncPolicy policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy required");
        }
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        this.activeFsyncPolicy = policy;
        WALShard[] current = ensureShardsOpen();
        for (WALShard shard : current) {
            shard.setFsyncPolicy(policy);
        }
        if (policy == WALConfig.FsyncPolicy.EVERY_SEC) {
            ensureFlusherStarted();
        }
    }

    /** 后台定时落盘：逐分片独立刷盘，互不阻塞其他分片。 */
    private void flushQuietlyAllShards() {
        if (closed || activeFsyncPolicy != WALConfig.FsyncPolicy.EVERY_SEC) {
            return;
        }
        WALShard[] current = this.shards;
        if (current == null) {
            return;
        }
        for (WALShard shard : current) {
            if (closed) {
                return;
            }
            shard.flushQuietly();
        }
    }

    /** 追加单条记录（按 key 路由到对应分片 WAL）。 */
    public void append(WALEntry entry) {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        int shardId = WalShardRouter.route(entry.key(), shardCount);
        ensureShardsOpen()[shardId].append(entry);
    }

    /** 零分配单条追加并登记在途应用低水位（供 WALStorageEngine 使用）。 */
    long appendSingleAndTrack(
            int shardId,
            WALEntry.Operation op,
            long timestamp,
            byte[] key,
            byte[] value,
            long ttlMillis) {
        return ensureShardsOpen()[shardId].appendSingleAndTrack(op, timestamp, key, value, ttlMillis, true);
    }

    /** 同分片零拷贝批量追加并登记低水位（v6.5.0 Phase 2 核心热路径）。 */
    long appendBatchAndTrack(int shardId, List<WriteOp> ops, long nowMillis) {
        return ensureShardsOpen()[shardId].appendBatchAndTrack(ops, nowMillis);
    }

    void completeApply(int shardId, long applyToken) {
        WALShard[] current = this.shards;
        if (current != null) {
            current[shardId].completeApply(applyToken);
        }
    }

    /** Group Commit / 写批累计提交批次数（用于观测与基准验证）。 */
    public long groupCommitBatches() {
        WALShard[] current = this.shards;
        if (current == null) {
            return 0L;
        }
        long sum = 0;
        for (WALShard shard : current) {
            sum += shard.writeBatches();
        }
        return sum;
    }

    /** Group Commit / 写批累计提交记录条数（用于观测与基准验证）。 */
    public long groupCommitRecords() {
        WALShard[] current = this.shards;
        if (current == null) {
            return 0L;
        }
        long sum = 0;
        for (WALShard shard : current) {
            sum += shard.writeRecords();
        }
        return sum;
    }

    public long totalBytesWritten() {
        WALShard[] current = this.shards;
        if (current == null) {
            return 0L;
        }
        long sum = 0;
        for (WALShard shard : current) {
            sum += shard.bytesWritten();
        }
        return sum;
    }

    public long totalFsyncCount() {
        WALShard[] current = this.shards;
        if (current == null) {
            return 0L;
        }
        long sum = 0;
        for (WALShard shard : current) {
            sum += shard.fsyncCount();
        }
        return sum;
    }

    public long totalFsyncNanos() {
        WALShard[] current = this.shards;
        if (current == null) {
            return 0L;
        }
        long sum = 0;
        for (WALShard shard : current) {
            sum += shard.fsyncTotalNanos();
        }
        return sum;
    }

    public long maxFsyncNanos() {
        WALShard[] current = this.shards;
        if (current == null) {
            return 0L;
        }
        long max = 0;
        for (WALShard shard : current) {
            max = Math.max(max, shard.fsyncMaxNanos());
        }
        return max;
    }

    public String activeIoBackend() {
        WALShard[] current = this.shards;
        return (current != null && current.length > 0) ? current[0].activeIoBackend() : "nio";
    }

    public boolean preallocateEnabled() {
        WALShard[] current = this.shards;
        return current != null && current.length > 0 && current[0].isPreallocated();
    }

    public void flushAndForce() {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        for (WALShard shard : ensureShardsOpen()) {
            shard.flushAndForce();
        }
    }

    public void rotate() {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        for (WALShard shard : ensureShardsOpen()) {
            shard.rotate();
        }
    }

    /** 当前写位置（单分片场景返回分片 0 位点）。 */
    public WALPosition position() {
        return ensureShardsOpen()[0].position();
    }

    /** 返回全部分片当前写位置向量。 */
    public List<WALPosition> shardPositions() {
        WALShard[] current = ensureShardsOpen();
        List<WALPosition> list = new ArrayList<>(shardCount);
        for (WALShard shard : current) {
            list.add(shard.position());
        }
        return list;
    }

    /** 已跨全部分片持久提交的最高 CLEAR_ALL 纪元（用于冷层 Manifest v2 对账）。 */
    public long committedClearEpoch() {
        return committedClearEpoch.get();
    }

    /** 下一次 clear() 分配纪元的单调下界（含恢复扫描到的撕裂未提交纪元与冷层纪元）。 */
    public long nextClearEpochFloor() {
        return nextClearEpochFloor.get();
    }

    /** 由冷层启动对账同步推进纪元下界，确保重启后绝不复用任何历史已提交或已落冷纪元。 */
    public void advanceEpochFloor(long floor) {
        if (floor > 0L) {
            nextClearEpochFloor.accumulateAndGet(floor, Math::max);
        }
    }

    @FunctionalInterface
    public interface IoSupplier<T> {
        T get() throws IOException;
    }

    /** L1 后台读锁：供 FlushManager.flush() 使用，与 clear() 互斥且不影响热路径吞吐。 */
    public <T> T withBackgroundTierReadLock(IoSupplier<T> action) throws IOException {
        backgroundTierLock.readLock().lock();
        try {
            return action.get();
        } finally {
            backgroundTierLock.readLock().unlock();
        }
    }

    /** L1 后台读锁：供 MigrationScheduler.runBatch() 使用。 */
    public void withBackgroundTierReadLock(Runnable action) {
        backgroundTierLock.readLock().lock();
        try {
            action.run();
        } finally {
            backgroundTierLock.readLock().unlock();
        }
    }

    /**
     * 独占清空屏障（严格遵循 7 层无环锁序 L1.writeLock -> L2(checkpointLock) -> L3(keyLocks[0..255])）：
     * 全部三层锁均支持可重入，供 TieringReadStorageEngine 与 WALStorageEngine 共用同一屏障。
     */
    public void runUnderClearBarrier(Runnable action) {
        backgroundTierLock.writeLock().lock();
        try {
            synchronized (checkpointLock) {
                int locked = 0;
                try {
                    for (int i = 0; i < keyLocks.length; i++) {
                        lockKeyStripe(i);
                        locked++;
                    }
                    action.run();
                } finally {
                    for (int i = locked - 1; i >= 0; i--) {
                        unlockKeyStripe(i);
                    }
                }
            }
        } finally {
            backgroundTierLock.writeLock().unlock();
        }
    }

    private void lockKeyStripe(int index) {
        keyLocks[index].lock();
    }

    private void unlockKeyStripe(int index) {
        keyLocks[index].unlock();
    }

    /**
     * 向全部 S 个 WAL 分片严格升序持有 L5(shardLock[0..S-1]) 写入 46B {@code CLEAR_ALL(epoch)} 并强制 {@code fdatasync}。
     * 若任一分片失败立即抛异常且不更新 {@code committedClearEpoch} / {@code clear.epoch}。
     */
    ClearBarrierCommit appendClearBarrierAllShards(long timestamp) {
        if (closed) {
            throw new WalWriteException("WAL is closed", null);
        }
        WALShard[] current = ensureShardsOpen();
        int lockedCount = 0;
        long epoch;
        long[] clearSeqs = new long[current.length];
        List<WALPosition> postPositions = new ArrayList<>(current.length);
        try {
            for (int i = 0; i < current.length; i++) {
                current[i].lockShard();
                lockedCount++;
            }
            if (closed) {
                throw new WalWriteException("WAL is closed", null);
            }
            epoch = nextClearEpochFloor.incrementAndGet();
            for (int i = 0; i < current.length; i++) {
                WALShard.ShardClearPosition pos = current[i].appendClearBarrierLocked(epoch, timestamp);
                clearSeqs[i] = pos.clearSegmentSequence();
                postPositions.add(pos.postPosition());
            }
        } finally {
            for (int i = lockedCount - 1; i >= 0; i--) {
                current[i].unlockShard();
            }
        }
        committedClearEpoch.accumulateAndGet(epoch, Math::max);
        try {
            writeClearEpochFile(config.directory(), epoch);
        } catch (IOException e) {
            throw new WalWriteException("WAL clear.epoch persist failed", e);
        }
        return new ClearBarrierCommit(epoch, clearSeqs, List.copyOf(postPositions));
    }

    /**
     * 在冷层与热层均已完成清空后，收尾更新既有 checkpoint.bin（若已存在）并安全清理严格早于 clearSeq[i] 的封闭旧段。
     */
    void finalizeClearPostStorage(ClearBarrierCommit commit) {
        Path cpFile = config.directory().resolve("checkpoint.bin");
        if (Files.exists(cpFile)) {
            try {
                if (shardCount == 1) {
                    CheckpointManager.write(config.directory(), commit.postPositions().get(0), List.of());
                } else {
                    CheckpointManager.writeSharded(config.directory(), commit.postPositions(), List.of());
                    ShardedRecoveryManager.writeMeta(config.directory(), shardCount);
                }
            } catch (IOException ignored) {
                // CLEAR_ALL(epoch) 已全分片 fdatasync 落盘，旧 checkpoint 恢复时会被屏障自动作废
            }
        }
        WALShard[] current = this.shards;
        if (current != null) {
            long[] clearSeqs = commit.clearSegmentSequences();
            for (int i = 0; i < current.length && i < clearSeqs.length; i++) {
                current[i].pruneSegmentsBefore(clearSeqs[i]);
            }
        }
    }

    record ClearBarrierCommit(long epoch, long[] clearSegmentSequences, List<WALPosition> postPositions) {
    }

    static long readClearEpochFile(Path directory) {
        Path file = directory.resolve(CLEAR_EPOCH_FILE);
        if (!Files.exists(file)) {
            return 0L;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("clearEpoch=")) {
                    long parsed = Long.parseLong(trimmed.substring("clearEpoch=".length()).trim());
                    return Math.max(0L, parsed);
                }
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    static void writeClearEpochFile(Path directory, long epoch) throws IOException {
        Files.createDirectories(directory);
        Path tmp = directory.resolve(CLEAR_EPOCH_FILE + ".tmp");
        byte[] bytes = ("clearEpoch=" + epoch + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer src = ByteBuffer.wrap(bytes);
            while (src.hasRemaining()) {
                ch.write(src);
            }
            ch.force(true);
        }
        Path target = directory.resolve(CLEAR_EPOCH_FILE);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        CheckpointManager.fsyncDirectory(directory);
    }

    public RecoveryManager.RecoveryStats recover(MemTable memTable) throws IOException {
        return recoverFrom(memTable, null);
    }

    /** 从指定单位置之后恢复（v1 checkpoint 兼容接口）。 */
    public RecoveryManager.RecoveryStats recoverFrom(
            MemTable memTable, long startSequence, long startOffset) throws IOException {
        CheckpointManager.Checkpoint cp = new CheckpointManager.Checkpoint(
                startSequence, startOffset, List.of());
        return recoverFrom(memTable, cp);
    }

    /**
     * 从 v1 或 v2 Checkpoint 位点向量并行恢复全部 WAL 分片：
     * 恢复前安全关闭活跃句柄以支持无冲突截断/迁移/重分片，恢复后重开分片句柄；
     * 若发生了旧版升级或重分片，自动执行一次新布局全量 Checkpoint 固化状态。
     */
    public RecoveryManager.RecoveryStats recoverFrom(
            MemTable memTable, CheckpointManager.Checkpoint checkpoint) throws IOException {
        boolean migratedOrResharded;
        RecoveryManager.RecoveryStats stats;
        synchronized (shardsLifecycleLock) {
            migratedOrResharded = hasPendingMigrationOrReshard();
            closeShardsLocked();
            if (shardCount == 1 && ShardedRecoveryManager.listShardDirectories(config.directory()).isEmpty()) {
                long seq = checkpoint == null ? 0L : checkpoint.segmentSequence();
                long off = checkpoint == null ? 0L : checkpoint.offset();
                stats = new RecoveryManager(config).recoverFrom(memTable, seq, off);
            } else {
                stats = new ShardedRecoveryManager(config).recoverFrom(memTable, checkpoint);
            }
            long recoveredCommitted = stats.committedClearEpoch();
            long recoveredMaxObserved = stats.maxObservedClearEpoch();
            long prevCommitted = committedClearEpoch.get();
            if (recoveredCommitted > 0L) {
                committedClearEpoch.accumulateAndGet(recoveredCommitted, Math::max);
            }
            long observedFloor = Math.max(recoveredCommitted, recoveredMaxObserved);
            if (observedFloor > 0L) {
                nextClearEpochFloor.accumulateAndGet(observedFloor, Math::max);
            }
            if (recoveredCommitted > prevCommitted) {
                try {
                    writeClearEpochFile(config.directory(), committedClearEpoch.get());
                } catch (IOException ignored) {
                    // 只读文件系统恢复演练下容忍元文件跳过
                }
            }
            openShardsLocked();
        }
        if (migratedOrResharded && memTable != null) {
            checkpoint(memTable);
        }
        return stats;
    }

    /**
     * 在线 Checkpoint（ADR-0016 + v6.5.0 低水位向量无 STW）：
     * 1) 逐分片采集安全低水位（包含在途未 apply 写的最小起始位点），无全局锁停顿；
     * 2) 无锁遍历 MemTable 快照；
     * 3) 原子写入 checkpoint.bin（单分片 v1 / 多分片 v2 + CRC32C）。
     */
    public void checkpoint(MemTable memTable) throws IOException {
        synchronized (checkpointLock) {
            WALShard[] current = ensureShardsOpen();
            List<WALPosition> safePositions = new ArrayList<>(shardCount);
            for (WALShard shard : current) {
                safePositions.add(shard.safeCheckpointPosition());
            }
            List<KeyValueEntry> entries = new ArrayList<>();
            try (var iterator = memTable.iterator()) {
                while (iterator.hasNext()) {
                    entries.add(iterator.next());
                }
            }
            if (shardCount == 1) {
                CheckpointManager.write(config.directory(), safePositions.get(0), entries);
            } else {
                CheckpointManager.writeSharded(config.directory(), safePositions, entries);
                ShardedRecoveryManager.writeMeta(config.directory(), shardCount);
            }
        }
    }

    /** 按键分片互斥（256 桶），用于协调热写路径与后台淘汰/迁移的 WAL 顺序。 */
    public <T> T withKeyLock(byte[] key, Supplier<T> action) {
        ReentrantLock lock = keyLocks[WalShardRouter.keyStripe(key)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    public void withKeyLock(byte[] key, Runnable action) {
        ReentrantLock lock = keyLocks[WalShardRouter.keyStripe(key)];
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 批量按键条带严格升序加锁（DAG 零死锁保证）：
     * 利用 256-bit 位图去重并按 0..255 升序依次加锁；因 16 分片 FNV-1a 与 256 桶同源，
     * 同分片批次至多触及 16 个独占条带，零跨分片竞争、零堆分配。
     */
    long withBatchKeyLocks(List<WriteOp> ops, LongSupplier action) {
        long w0 = 0L, w1 = 0L, w2 = 0L, w3 = 0L;
        for (int i = 0; i < ops.size(); i++) {
            int stripe = WalShardRouter.keyStripe(ops.get(i).key());
            int word = stripe >>> 6;
            long bit = 1L << (stripe & 63);
            switch (word) {
                case 0 -> w0 |= bit;
                case 1 -> w1 |= bit;
                case 2 -> w2 |= bit;
                default -> w3 |= bit;
            }
        }
        lockWord(w0, 0);
        try {
            lockWord(w1, 64);
            try {
                lockWord(w2, 128);
                try {
                    lockWord(w3, 192);
                    try {
                        return action.getAsLong();
                    } finally {
                        unlockWord(w3, 192);
                    }
                } finally {
                    unlockWord(w2, 128);
                }
            } finally {
                unlockWord(w1, 64);
            }
        } finally {
            unlockWord(w0, 0);
        }
    }

    private void lockWord(long mask, int baseIndex) {
        long m = mask;
        while (m != 0L) {
            int bit = Long.numberOfTrailingZeros(m);
            keyLocks[baseIndex + bit].lock();
            m &= (m - 1L);
        }
    }

    private void unlockWord(long mask, int baseIndex) {
        long m = mask;
        while (m != 0L) {
            int bit = Long.numberOfTrailingZeros(m);
            keyLocks[baseIndex + bit].unlock();
            m &= (m - 1L);
        }
    }

    private static ReentrantLock[] createKeyLocks() {
        ReentrantLock[] locks = new ReentrantLock[256];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    public CheckpointManager.Checkpoint loadCheckpoint() {
        return CheckpointManager.read(config.directory());
    }

    @Override
    public void close() throws IOException {
        closed = true;
        ScheduledExecutorService executorToStop;
        synchronized (flusherLock) {
            executorToStop = flusher;
            flusher = null;
        }
        if (executorToStop != null) {
            executorToStop.shutdownNow();
        }
        synchronized (shardsLifecycleLock) {
            closeShardsLocked();
        }
    }

    public record WALPosition(long sequence, long offset) {
    }
}
