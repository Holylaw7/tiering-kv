package io.tieringkv.storage.memory;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 内存热层：64 段 SkipList + 分段读写锁（ADR-0007 / ADR-0008）。
 *
 * <p>写入单写者每段；读取读锁 + 惰性过期检查；DELETE 使用 tombstone；
 * TTL 采用惰性 + 主动混合（ADR-0009）；内存压力回调在锁外触发。
 */
public final class MemTable implements StorageEngine, AutoCloseable {

    public static final int SEGMENT_COUNT = 64;
    private static final long DEFAULT_TTL_INTERVAL_MILLIS = 1000;

    private final Segment[] segments = new Segment[SEGMENT_COUNT];
    private final MemoryManager memoryManager;
    private final Version version = new Version();
    private final TimeSource timeSource;
    private final AtomicLong liveSize = new AtomicLong();
    private final TTLManager ttlManager;
    private final ScheduledExecutorService scheduler;

    private MemTable(MemoryManager memoryManager, TimeSource timeSource, boolean startTtlScheduler) {
        this.memoryManager = memoryManager;
        this.timeSource = timeSource;
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segments[i] = new Segment();
        }
        if (startTtlScheduler) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "ttl-expirer");
                thread.setDaemon(true);
                return thread;
            });
        } else {
            scheduler = null;
        }
        ttlManager = new TTLManager(this, scheduler, DEFAULT_TTL_INTERVAL_MILLIS);
        ttlManager.start();
    }

    /** 生产默认：真实时钟 + 后台 TTL 清扫线程。 */
    public static MemTable create() {
        return new MemTable(new MemoryManager(Long.MAX_VALUE), System::currentTimeMillis, true);
    }

    public static MemTable create(MemoryManager memoryManager) {
        return new MemTable(memoryManager, System::currentTimeMillis, true);
    }

    /** 测试用：可注入时钟、无后台线程，通过 {@link #activeExpire()} 手动清扫。 */
    public static MemTable createForTest(TimeSource timeSource, MemoryManager memoryManager) {
        return new MemTable(memoryManager, timeSource, false);
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        if (ttlMillis != NO_TTL && ttlMillis <= 0) {
            // Redis 语义：SET ... EX 0 立即过期（等价于删除）
            expireImmediately(key);
            return;
        }
        long now = timeSource.nowMillis();
        long versionId = version.next();
        KeyValueEntry entry = KeyValueEntry.live(key, value, now, ttlMillis, versionId);
        int segmentIndex = segmentIndex(key);
        Segment segment = segments[segmentIndex];
        segment.lock.writeLock().lock();
        try {
            KeyValueEntry old = segment.list.get(key);
            if (old != null) {
                memoryManager.remove(old.size());
                if (old.isLive(now)) {
                    liveSize.decrementAndGet();
                }
            }
            segment.list.put(entry);
            memoryManager.add(entry.size());
            liveSize.incrementAndGet();
            if (entry.expireTimestamp() >= 0) {
                ttlManager.schedule(entry.expireTimestamp(), versionId, segmentIndex, key);
            }
        } finally {
            segment.lock.writeLock().unlock();
        }
        notifyMemoryPressureIfNeeded();
    }

    @Override
    public byte[] get(byte[] key) {
        long now = timeSource.nowMillis();
        Segment segment = segments[segmentIndex(key)];
        segment.lock.readLock().lock();
        try {
            KeyValueEntry entry = segment.list.get(key);
            return entry != null && entry.isLive(now) ? entry.value() : null;
        } finally {
            segment.lock.readLock().unlock();
        }
    }

    /** 返回当前条目（可能为 tombstone / 过期），供淘汰管理校验；不克隆。 */
    public KeyValueEntry getEntry(byte[] key) {
        Segment segment = segments[segmentIndex(key)];
        segment.lock.readLock().lock();
        try {
            return segment.list.get(key);
        } finally {
            segment.lock.readLock().unlock();
        }
    }

    @Override
    public boolean delete(byte[] key) {
        long now = timeSource.nowMillis();
        Segment segment = segments[segmentIndex(key)];
        segment.lock.writeLock().lock();
        try {
            KeyValueEntry current = segment.list.get(key);
            if (current == null || !current.isLive(now)) {
                return false;
            }
            KeyValueEntry tombstone = KeyValueEntry.tombstone(key, now, version.next());
            segment.list.put(tombstone);
            memoryManager.remove(current.size());
            memoryManager.add(tombstone.size());
            liveSize.decrementAndGet();
            return true;
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    /**
     * 物理移除（淘汰专用）：不产生 tombstone，立即回收内存。
     * 用户 DEL 仍走 tombstone（WAL / Snapshot / LSM 需要删除历史）。
     */
    public boolean removePhysical(byte[] key) {
        long now = timeSource.nowMillis();
        Segment segment = segments[segmentIndex(key)];
        segment.lock.writeLock().lock();
        try {
            KeyValueEntry current = segment.list.get(key);
            if (current == null || !current.isLive(now)) {
                return false;
            }
            segment.list.remove(key);
            memoryManager.remove(current.size());
            liveSize.decrementAndGet();
            return true;
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    @Override
    public boolean exists(byte[] key) {
        long now = timeSource.nowMillis();
        Segment segment = segments[segmentIndex(key)];
        segment.lock.readLock().lock();
        try {
            KeyValueEntry entry = segment.list.get(key);
            return entry != null && entry.isLive(now);
        } finally {
            segment.lock.readLock().unlock();
        }
    }

    @Override
    public long size() {
        return liveSize.get();
    }

    @Override
    public StorageIterator iterator() {
        long now = timeSource.nowMillis();
        List<List<KeyValueEntry>> perSegment = new ArrayList<>(SEGMENT_COUNT);
        for (Segment segment : segments) {
            segment.lock.readLock().lock();
            try {
                List<KeyValueEntry> live = new ArrayList<>();
                for (KeyValueEntry entry : segment.list.entriesInOrder()) {
                    if (entry.isLive(now)) {
                        live.add(entry);
                    }
                }
                perSegment.add(live);
            } finally {
                segment.lock.readLock().unlock();
            }
        }
        return new MergingIterator(perSegment);
    }

    /**
     * 供 TTLManager 调用：仅当版本与过期时间均匹配且确实到期时物理移除，
     * 防止误删"已重新设置 TTL"的新数据（ADR-0009 版本守卫）。
     */
    boolean expireIfMatches(int segmentIndex, byte[] key, long versionId, long expireMillis) {
        long now = timeSource.nowMillis();
        Segment segment = segments[segmentIndex];
        segment.lock.writeLock().lock();
        try {
            KeyValueEntry current = segment.list.get(key);
            if (current == null
                    || current.version() != versionId
                    || current.expireTimestamp() != expireMillis
                    || !current.isExpired(now)) {
                return false;
            }
            segment.list.remove(key);
            memoryManager.remove(current.size());
            liveSize.decrementAndGet();
            return true;
        } finally {
            segment.lock.writeLock().unlock();
        }
    }

    /** 主动过期入口：后台线程周期调用；测试可直接调用。 */
    public long activeExpire() {
        return ttlManager.expireOnce();
    }

    long nowMillis() {
        return timeSource.nowMillis();
    }

    public MemoryManager memoryManager() {
        return memoryManager;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private void expireImmediately(byte[] key) {
        long now = timeSource.nowMillis();
        Segment segment = segments[segmentIndex(key)];
        segment.lock.writeLock().lock();
        try {
            KeyValueEntry old = segment.list.get(key);
            if (old != null && old.isLive(now)) {
                segment.list.remove(key);
                memoryManager.remove(old.size());
                liveSize.decrementAndGet();
            }
        } finally {
            segment.lock.writeLock().unlock();
        }
        notifyMemoryPressureIfNeeded();
    }

    private void notifyMemoryPressureIfNeeded() {
        if (memoryManager.isOverLimit()) {
            EvictionCallback callback = memoryManager.evictionCallback();
            if (callback != null) {
                callback.onMemoryPressure(memoryManager.usedBytes(), memoryManager.maxBytes());
            }
        }
    }

    private int segmentIndex(byte[] key) {
        return fnv1a(key) & (SEGMENT_COUNT - 1);
    }

    /** FNV-1a 32 位哈希：确定性、分布均匀、与 JVM 无关。 */
    private static int fnv1a(byte[] data) {
        int hash = 0x811c9dc5;
        for (byte b : data) {
            hash ^= (b & 0xff);
            hash *= 0x01000193;
        }
        return hash;
    }

    private static final class Segment {
        private final SkipList list = new SkipList();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    }

    /** 64 段有序列表的多路归并迭代器（弱一致快照，ADR-0008）。 */
    private static final class MergingIterator implements StorageIterator {

        private final PriorityQueue<SegmentCursor> heap;

        private MergingIterator(List<List<KeyValueEntry>> perSegment) {
            heap = new PriorityQueue<>(perSegment.size(),
                    Comparator.comparing((SegmentCursor cursor) -> cursor.peek().key(),
                            (a, b) -> Arrays.compareUnsigned(a, b)));
            for (List<KeyValueEntry> entries : perSegment) {
                if (!entries.isEmpty()) {
                    heap.offer(new SegmentCursor(entries));
                }
            }
        }

        @Override
        public boolean hasNext() {
            return !heap.isEmpty();
        }

        @Override
        public KeyValueEntry next() {
            SegmentCursor cursor = heap.poll();
            KeyValueEntry entry = cursor.peek();
            cursor.advance();
            if (cursor.hasNext()) {
                heap.offer(cursor);
            }
            return entry;
        }

        @Override
        public void close() {
            // 快照迭代器：无资源需释放
        }

        private static final class SegmentCursor {
            private final List<KeyValueEntry> entries;
            private int index;

            private SegmentCursor(List<KeyValueEntry> entries) {
                this.entries = entries;
            }

            private KeyValueEntry peek() {
                return entries.get(index);
            }

            private boolean hasNext() {
                return index < entries.size();
            }

            private void advance() {
                index++;
            }
        }
    }
}
