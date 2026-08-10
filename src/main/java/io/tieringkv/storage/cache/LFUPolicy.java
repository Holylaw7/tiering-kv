package io.tieringkv.storage.cache;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * LFU 淘汰策略（ADR-0010/0011，默认策略）。
 * HotnessTracker 维护热度数据；快照索引 TreeSet 保证 O(logN) 更新、O(1) 候选。
 */
public final class LFUPolicy implements EvictionPolicy {

    private final HotnessTracker tracker;
    private final Object indexLock = new Object();
    private final Map<ByteBuffer, IndexedKey> byKey = new HashMap<>();
    private final TreeSet<IndexedKey> index = new TreeSet<>(
            Comparator.comparingLong(IndexedKey::frequency)
                    .thenComparingLong(IndexedKey::lastAccessTime)
                    .thenComparing(IndexedKey::key));

    public LFUPolicy(HotnessTracker tracker) {
        this.tracker = tracker;
    }

    public LFUPolicy(long decayIntervalMillis) {
        this(new HotnessTracker(decayIntervalMillis));
    }

    @Override
    public String name() {
        return "lfu";
    }

    @Override
    public void onAccess(AccessEvent event) {
        ByteBuffer key = ByteBuffer.wrap(event.key());
        synchronized (indexLock) {
            IndexedKey old = byKey.remove(key);
            if (old != null) {
                index.remove(old);
            }
            if (event.operation() == AccessEvent.AccessOperation.DELETE
                    || event.operation() == AccessEvent.AccessOperation.EVICT) {
                tracker.record(event);
                return;
            }
            HotnessEntry entry = tracker.record(event);
            IndexedKey fresh = new IndexedKey(key, entry.frequency(), entry.lastAccessTime());
            byKey.put(key, fresh);
            index.add(fresh);
        }
    }

    @Override
    public EvictionCandidate selectCandidate() {
        synchronized (indexLock) {
            if (index.isEmpty()) {
                return null;
            }
            IndexedKey first = index.first();
            HotnessEntry entry = tracker.get(first.key().array());
            if (entry == null) {
                return null;
            }
            return new EvictionCandidate(entry.key(), entry.frequency(),
                    entry.lastAccessTime(), entry.sizeBytes(), entry.frequency());
        }
    }

    public HotnessTracker tracker() {
        return tracker;
    }

    /** 索引项为不可变快照，避免可变比较键破坏 TreeSet 结构。 */
    private record IndexedKey(ByteBuffer key, long frequency, long lastAccessTime) {
    }
}
