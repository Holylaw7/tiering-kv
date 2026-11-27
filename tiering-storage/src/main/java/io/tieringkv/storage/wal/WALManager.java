package io.tieringkv.storage.wal;

import io.tieringkv.storage.WriteOp;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
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

    private final WALConfig config;
    private final int shardCount;
    private final Object shardsLifecycleLock = new Object();
    private volatile WALShard[] shards;
    private final Object checkpointLock = new Object();
    private final ReentrantLock[] keyLocks = createKeyLocks();
    private final Object flusherLock = new Object();
    private volatile WALConfig.FsyncPolicy activeFsyncPolicy;
    private volatile boolean closed;
    /** EVERY_SEC 的后台落盘器（TD-103）：逐分片独立刷盘，互不阻塞。 */
    private ScheduledExecutorService flusher;

    public WALManager(WALConfig config) throws IOException {
        this.config = config;
        this.shardCount = config.shardCount();
        this.activeFsyncPolicy = config.fsyncPolicy();
        Files.createDirectories(config.directory());
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
