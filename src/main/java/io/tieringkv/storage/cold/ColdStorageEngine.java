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
    private boolean closed;

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
    }

    @Override
    public byte[] get(byte[] key) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            KeyValueEntry entry = pending.get(Keys.wrap(key));
            if (entry == null) {
                for (int i = tables.size() - 1; i >= 0; i--) {
                    entry = blockCache == null
                            ? readEntry(tables.get(i), key)
                            : readEntryWithCache(tables.get(i), key);
                    if (entry != null) {
                        break;
                    }
                }
            }
            if (entry == null) {
                return null;
            }
            // 首个命中的条目决定结果：tombstone/过期视为不存在（不再查旧表）
            return entry.isLive(now) ? entry.value() : null;
        }
    }

    @Override
    public void put(KeyValueEntry entry) {
        synchronized (lock) {
            ensureOpen();
            pending.put(Keys.wrap(entry.key()), entry);
            pendingBytes += entry.size();
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
            SSTableMeta output = new CompactionTask(
                    config, inputs, nextTableId).run();
            nextTableId++;
            installCompaction(output, inputs);
            leveled.promote(from, output);
            return output;
        }
    }

    public List<SSTableMeta> tablesSnapshot() {
        synchronized (lock) {
            return List.copyOf(tables);
        }
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
        SSTableMeta output = new CompactionTask(
                config, inputs, nextTableId).run();
        nextTableId++;
        installCompaction(output, inputs);
        return output;
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
