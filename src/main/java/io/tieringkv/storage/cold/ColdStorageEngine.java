package io.tieringkv.storage.cold;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CacheKey;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.io.MmapSSTableReader;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 冷存储引擎（ADR-0017）：pending 缓冲 + SSTable 列表 + Manifest。
 * 读取顺序：pending → 新表 → 旧表；compaction 全量合并（ADR-0019）。
 */
public final class ColdStorageEngine implements TierStorage, AutoCloseable {

    public record Config(
            Path directory,
            int blockTargetBytes,
            double bloomBitsPerKey,
            long pendingThresholdBytes,
            int compactionThreshold) {

        public static Config defaults(Path directory) {
            return new Config(directory, 4096, 10, 4L << 20, 8);
        }
    }

    private final Config config;
    private final Object lock = new Object();
    private final TreeMap<ByteBuffer, KeyValueEntry> pending = new TreeMap<>();
    private final List<SSTableMeta> tables = new ArrayList<>(); // 旧 → 新
    private final Map<Long, SSTableReader> readers = new HashMap<>();
    private final CompactionManager compactionManager;
    private final BlockCache blockCache;
    private final IOStatistics ioStats;
    private final boolean useMmap;
    private long nextTableId = 1;
    private long pendingBytes;
    /** 存活键计数缓存（TD-086）：写入置脏，查询时惰性重算。 */
    /**
     * 存活键计数（F1，ADR-0370）：对上层只暴露快照值，不在 INFO 请求线程里全量扫描。
     *
     * <p>写入只置脏（{@code liveCountDirty}），由后台线程定期重算并发布；
     * 构造时同步算一次，避免刚启动时 INFO 报 0。
     */
    private volatile boolean liveCountDirty = true;
    private volatile long liveCountCache;
    private volatile boolean liveCountKnown;
    private Thread liveCountRefresher;
    private boolean closed;
    /** Compaction 是否正在执行（INFO 用，TD-097）。 */
    private final java.util.concurrent.atomic.AtomicBoolean compacting =
            new java.util.concurrent.atomic.AtomicBoolean();
    /** 合并回调（TD-096）：由上层接线到 StorageMetrics，避免冷层反向依赖 tiering。 */
    private volatile CompactionListener compactionListener;

    /** 合并事件监听（输入字节 / 输出字节 / 耗时）。 */
    public interface CompactionListener {
        void onCompaction(long inputBytes, long outputBytes, long latencyNanos);
    }

    public void setCompactionListener(CompactionListener listener) {
        this.compactionListener = listener;
    }

    public boolean isCompacting() {
        return compacting.get();
    }

    public ColdStorageEngine(Config config) throws IOException {
        this(config, null, null, false);
    }

    /** IO 优化构造：mmap 读取 + BlockCache（均为可选）。 */
    public ColdStorageEngine(
            Config config,
            BlockCache blockCache,
            IOStatistics ioStats,
            boolean useMmap) throws IOException {
        this.config = config;
        this.blockCache = blockCache;
        this.ioStats = ioStats;
        this.useMmap = useMmap;
        Files.createDirectories(config.directory());
        List<SSTableMeta> loaded = Manifest.read(config.directory());
        tables.addAll(loaded);
        nextTableId = loaded.stream().mapToLong(SSTableMeta::id).max().orElse(0) + 1;
        this.compactionManager = new CompactionManager(this);
        if (ioStats != null) {
            ioStats.setMappedBytes(loaded.stream().mapToLong(SSTableMeta::fileSize).sum());
        }
        // 启动时算一次，之后交给后台线程；INFO 只读快照
        try {
            refreshLiveCount();
        } catch (RuntimeException ex) {
            // 计数失败不影响读写，后台线程会重试
            liveCountCache = 0;
            liveCountKnown = false;
        }
        startLiveCountRefresher();
    }

    @Override
    public byte[] get(byte[] key) {
        KeyValueEntry entry = entry(key);
        return entry == null ? null : entry.value();
    }

    /**
     * 读取存活条目（TD-086）：与 {@link #get} 的可见性一致，但保留 TTL 等元数据，
     * 供上层把冷层数据回填热层时还原过期时间。
     *
     * @return 存活条目；不存在、已删除或已过期返回 null
     */
    public KeyValueEntry entry(byte[] key) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            KeyValueEntry entry = findEntryLocked(key);
            // 首个命中的条目决定结果：tombstone/过期视为不存在（不再查旧表）
            return entry != null && entry.isLive(now) ? entry : null;
        }
    }

    /**
     * 冷层存活键数（精确，供 DBSIZE 汇总）。
     *
     * <p>实现取舍：键可能经由 `put`（逐条迁移）或 `writeTable`（整表 flush /
     * compaction）进入冷层，逐条维护计数器需要在整表写入时做 N 次历史查询，
     * 代价过高。这里改为**脏标记 + 惰性全量计数**：发生写入后首次查询触发
     * 一次迭代，之后命中缓存，直到下一次写入。
     */
    public long liveCount() {
        // F1：只读快照，绝不在此处扫描冷层
        return liveCountCache;
    }

    /** 重新扫描并发布计数快照（后台线程与测试使用，不在 INFO 路径调用）。 */
    public void refreshLiveCount() {
        synchronized (lock) {
            long counted = 0;
            try (StorageIterator iterator = iterator()) {
                while (iterator.hasNext()) {
                    iterator.next();
                    counted++;
                }
            }
            liveCountCache = counted;
            liveCountDirty = false;
            liveCountKnown = true;
        }
    }

    /** 计数快照是否已就绪（供测试与诊断）。 */
    public boolean liveCountKnown() {
        return liveCountKnown;
    }

    /** 是否有未合并到快照的写入（供测试与诊断）。 */
    public boolean liveCountStale() {
        return liveCountDirty;
    }

    /** 后台校正：单线程、串行、失败保留上一次有效快照。 */
    private void startLiveCountRefresher() {
        Thread thread = new Thread(() -> {
            while (!closed) {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!liveCountDirty || closed) {
                    continue;
                }
                try {
                    refreshLiveCount();
                } catch (RuntimeException ex) {
                    // 保留旧快照，下个周期重试
                }
            }
        }, "cold-live-count-refresher");
        thread.setDaemon(true);
        this.liveCountRefresher = thread;
        thread.start();
    }

    private void invalidateLiveCount() {
        liveCountDirty = true;
    }

    private KeyValueEntry findEntryLocked(byte[] key) {
        KeyValueEntry entry = pending.get(Keys.wrap(key));
        if (entry != null) {
            return entry;
        }
        for (int i = tables.size() - 1; i >= 0; i--) {
            entry = blockCache == null
                    ? readEntry(tables.get(i), key)
                    : readEntryWithCache(tables.get(i), key);
            if (entry != null) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public void put(KeyValueEntry entry) {
        synchronized (lock) {
            ensureOpen();
            pending.put(Keys.wrap(entry.key()), entry);
            pendingBytes += entry.size();
            invalidateLiveCount();
            if (pendingBytes >= config.pendingThresholdBytes()) {
                flushPendingLocked();
            }
        }
    }

    @Override
    public void delete(byte[] key) {
        put(KeyValueEntry.tombstone(key, System.currentTimeMillis(), System.nanoTime()));
    }

    /** 快照迭代（pending + 所有表，最新优先），过滤 tombstone 与过期。 */
    public StorageIterator iterator() {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            List<KeyValueEntry> pendingSnapshot = new ArrayList<>(pending.values());
            List<MergingIterator.Source> sources = new ArrayList<>();
            int priority = tables.size() + 1;
            sources.add(new MergingIterator.Source(
                    new MergingIterator.ListIterator(pendingSnapshot), priority));
            for (int i = tables.size() - 1; i >= 0; i--) {
                SSTableMeta meta = tables.get(i);
                try {
                    sources.add(new MergingIterator.Source(readerFor(meta).iterator(), i));
                } catch (IOException e) {
                    throw new ColdCorruptionException(
                            "iterate " + meta.fileName() + " failed: " + e.getMessage());
                }
            }
            MergingIterator merged = new MergingIterator(sources);
            return new FilteringIterator(merged, now);
        }
    }

    /** 直接把有序快照写成新表（FlushManager 用）；表内键唯一、升序。 */
    public SSTableMeta writeTable(List<KeyValueEntry> entries) throws IOException {
        synchronized (lock) {
            ensureOpen();
            SSTableMeta meta;
            try (SSTableWriter writer = new SSTableWriter(config.directory(), nextTableId,
                    Math.max(1, entries.size()), config.bloomBitsPerKey(),
                    config.blockTargetBytes())) {
                for (KeyValueEntry entry : entries) {
                    writer.writeEntry(entry);
                }
                meta = writer.finish();
            }
            nextTableId++;
            tables.add(meta);
            Manifest.write(config.directory(), tables);
            invalidateLiveCount();
            compactIfNeededLocked();
            return meta;
        }
    }

    public boolean compactIfNeeded() {
        synchronized (lock) {
            return compactIfNeededLocked();
        }
    }

    public SSTableMeta compactAll() throws IOException {
        synchronized (lock) {
            return compactAllLocked();
        }
    }

    /** Leveled compaction（ADR-0323）：合并 nextMergeLevel 与下一级表。 */
    public SSTableMeta compactLeveled(LeveledCompaction leveled)
            throws IOException {
        if (leveled == null) {
            throw new IllegalArgumentException(
                    "leveled compaction required");
        }
        synchronized (lock) {
            int from = leveled.nextMergeLevel();
            if (from < 0) {
                return null;
            }
            List<SSTableMeta> inputs = new ArrayList<>();
            inputs.addAll(leveled.tablesAt(from));
            inputs.addAll(leveled.tablesAt(from + 1));
            if (inputs.size() < 2) {
                return null;
            }
            long startedAt = System.nanoTime();
            SSTableMeta output = new CompactionTask(
                    config, inputs, nextTableId).run();
            nextTableId++;
            installCompaction(output, inputs);
            leveled.promote(from, output);
            notifyCompaction(inputs, output, System.nanoTime() - startedAt);
            return output;
        }
    }

    private final java.util.concurrent.atomic.AtomicLong tablesSnapshotCalls =
            new java.util.concurrent.atomic.AtomicLong();

    /** 冷层 pending 条目数（O(1)，供观测用）。 */
    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** 冷层 pending 字节数（O(1)，供观测用）。 */
    public long pendingByteSize() {
        synchronized (lock) {
            return pendingBytes;
        }
    }

    public List<SSTableMeta> tablesSnapshot() {
        tablesSnapshotCalls.incrementAndGet();
        synchronized (lock) {
            return List.copyOf(tables);
        }
    }

    /** 诊断用：tablesSnapshot 被调用的次数（验证写路径不再触发全量复制）。 */
    public long tablesSnapshotCalls() {
        return tablesSnapshotCalls.get();
    }

    CompactionManager compactionManager() {
        return compactionManager;
    }

    Config config() {
        return config;
    }

    long nextTableId() {
        return nextTableId;
    }

    void installCompaction(SSTableMeta output, List<SSTableMeta> inputs) throws IOException {
        for (SSTableMeta input : inputs) {
            if (blockCache != null) {
                blockCache.invalidate(input.id());
            }
            SSTableReader reader = readers.remove(input.id());
            if (reader != null) {
                reader.close();
            }
            Files.deleteIfExists(input.path(config.directory()));
        }
        tables.clear();
        tables.add(output);
        nextTableId = Math.max(nextTableId, output.id() + 1);
        Manifest.write(config.directory(), tables);
    }

    private void notifyCompaction(List<SSTableMeta> inputs, SSTableMeta output, long latencyNanos) {
        CompactionListener listener = this.compactionListener;
        if (listener == null) {
            return;
        }
        long inputBytes = inputs.stream().mapToLong(SSTableMeta::fileSize).sum();
        long outputBytes = output == null ? 0 : output.fileSize();
        listener.onCompaction(inputBytes, outputBytes, latencyNanos);
    }

    private boolean compactIfNeededLocked() {
        if (tables.size() < config.compactionThreshold()) {
            return false;
        }
        try {
            compactAllLocked();
            return true;
        } catch (IOException e) {
            throw new ColdCorruptionException("compaction failed: " + e.getMessage());
        }
    }

    private SSTableMeta compactAllLocked() throws IOException {
        if (tables.size() < 2) {
            return null;
        }
        List<SSTableMeta> inputs = new ArrayList<>(tables);
        long startedAt = System.nanoTime();
        compacting.set(true);
        try {
            SSTableMeta output = new CompactionTask(
                    config, inputs, nextTableId).run();
            nextTableId++;
            installCompaction(output, inputs);
            notifyCompaction(inputs, output, System.nanoTime() - startedAt);
            return output;
        } finally {
            compacting.set(false);
        }
    }

    private void flushPendingLocked() {
        if (pending.isEmpty()) {
            return;
        }
        try {
            List<KeyValueEntry> entries = new ArrayList<>(pending.values());
            SSTableMeta meta;
            try (SSTableWriter writer = new SSTableWriter(config.directory(), nextTableId,
                    entries.size(), config.bloomBitsPerKey(), config.blockTargetBytes())) {
                for (KeyValueEntry entry : entries) {
                    writer.writeEntry(entry);
                }
                meta = writer.finish();
            }
            nextTableId++;
            tables.add(meta);
            pending.clear();
            pendingBytes = 0;
            Manifest.write(config.directory(), tables);
            compactIfNeededLocked();
        } catch (IOException e) {
            throw new ColdCorruptionException("pending flush failed: " + e.getMessage());
        }
    }

    private KeyValueEntry readEntry(SSTableMeta meta, byte[] key) {
        try {
            return readerFor(meta).get(key);
        } catch (IOException e) {
            throw new ColdCorruptionException("read " + meta.fileName() + " failed: " + e.getMessage());
        }
    }

    /** BlockCache 路径：cache hit 直接解码；miss → mmap/FileChannel 读 + 回填。 */
    private KeyValueEntry readEntryWithCache(SSTableMeta meta, byte[] key) {
        try {
            SSTableReader reader = readerFor(meta);
            if (!reader.mightContain(key)) {
                return null;
            }
            BlockIndex.IndexEntry blockEntry = reader.locateBlock(key);
            CacheKey cacheKey = new CacheKey(meta.id(), blockEntry.offset());
            ByteBuffer cached = blockCache.get(cacheKey);
            java.util.List<KeyValueEntry> entries;
            if (cached != null) {
                ioStats.recordCacheHit();
                entries = Block.decode(cached);
            } else {
                ioStats.recordCacheMiss();
                long t0 = System.nanoTime();
                ByteBuffer raw = reader.readBlockBuffer(blockEntry);
                ioStats.recordRead(System.nanoTime() - t0);
                blockCache.put(cacheKey, raw);
                entries = Block.decode(raw);
            }
            int position = SSTableReader.binarySearchEntries(entries, key);
            return position >= 0 ? entries.get(position) : null;
        } catch (IOException e) {
            throw new ColdCorruptionException(
                    "read " + meta.fileName() + " failed: " + e.getMessage());
        }
    }

    private SSTableReader readerFor(SSTableMeta meta) {
        return readers.computeIfAbsent(meta.id(), id -> {
            try {
                return useMmap
                        ? MmapSSTableReader.open(meta, config.directory())
                        : SSTableReader.open(meta, config.directory());
            } catch (IOException e) {
                throw new ColdCorruptionException("open " + meta.fileName() + " failed: " + e.getMessage());
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("cold storage is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (liveCountRefresher != null) {
            liveCountRefresher.interrupt();
        }
        synchronized (lock) {
            for (SSTableReader reader : readers.values()) {
                reader.close();
            }
            readers.clear();
            closed = true;
        }
    }

    /** 过滤 tombstone / 过期条目的迭代包装。 */
    private static final class FilteringIterator implements StorageIterator {
        private final StorageIterator delegate;
        private final long now;
        private KeyValueEntry next;

        private FilteringIterator(StorageIterator delegate, long now) {
            this.delegate = delegate;
            this.now = now;
            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public KeyValueEntry next() {
            KeyValueEntry entry = next;
            advance();
            return entry;
        }

        private void advance() {
            next = null;
            while (delegate.hasNext()) {
                KeyValueEntry candidate = delegate.next();
                if (candidate.isLive(now)) {
                    next = candidate;
                    break;
                }
            }
        }

        @Override
        public void close() {
        }
    }
}