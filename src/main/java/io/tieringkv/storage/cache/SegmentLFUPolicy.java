package io.tieringkv.storage.cache;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 分段 LFU + 异步更新缓冲（ADR-0327，TD-006）：onAccess 无锁入队，
 * selectCandidate/drain 时合并到各段索引（16 段独立锁），
 * 降低全局同步段竞争。
 */
public final class SegmentLFUPolicy implements EvictionPolicy {

    public static final int DEFAULT_SEGMENTS = 16;

    private final SegmentLfu[] segments;
    private final Queue<AccessEvent> buffer =
            new ConcurrentLinkedQueue<>();

    public SegmentLFUPolicy() {
        this(DEFAULT_SEGMENTS);
    }

    public SegmentLFUPolicy(int segments) {
        if (segments <= 0) {
            throw new IllegalArgumentException(
                    "segments must be positive");
        }
        this.segments = new SegmentLfu[segments];
        for (int i = 0; i < segments; i++) {
            this.segments[i] = new SegmentLfu();
        }
    }

    @Override
    public String name() {
        return "segment-lfu";
    }

    /** 热路径：仅入队（无锁）。 */
    @Override
    public void onAccess(AccessEvent event) {
        buffer.offer(event);
    }

    /** 合并缓冲事件到各段索引；返回处理数量。 */
    public int drain() {
        int drained = 0;
        AccessEvent event;
        while ((event = buffer.poll()) != null) {
            segments[segmentOf(event.key())].onAccess(event);
            drained++;
        }
        return drained;
    }

    public int buffered() {
        return buffer.size();
    }

    /** 先 drain 再选出全局最小候选。 */
    @Override
    public EvictionCandidate selectCandidate() {
        drain();
        EvictionCandidate best = null;
        for (SegmentLfu segment : segments) {
            EvictionCandidate candidate = segment.candidate();
            if (candidate != null && (best == null
                    || candidate.score() < best.score())) {
                best = candidate;
            }
        }
        return best;
    }

    private int segmentOf(byte[] key) {
        return (java.util.Arrays.hashCode(key) & Integer.MAX_VALUE)
                % segments.length;
    }

    /** 单段 LFU：HashMap + TreeSet + 段锁，衰减按访问间隔。 */
    private static final class SegmentLfu {

        private static final long DECAY_INTERVAL_MILLIS = 60_000;

        private final Object lock = new Object();
        private final Map<ByteBuffer, IndexedKey> byKey =
                new HashMap<>();
        private final TreeSet<IndexedKey> index = new TreeSet<>(
                Comparator.comparingLong(IndexedKey::frequency)
                        .thenComparingLong(IndexedKey::lastAccessTime)
                        .thenComparing(key -> ByteBuffer.wrap(key.key())));

        void onAccess(AccessEvent event) {
            ByteBuffer key = ByteBuffer.wrap(event.key());
            synchronized (lock) {
                IndexedKey old = byKey.remove(key);
                if (old != null) {
                    index.remove(old);
                }
                if (event.operation() == AccessEvent.AccessOperation.DELETE
                        || event.operation()
                        == AccessEvent.AccessOperation.EVICT) {
                    return;
                }
                long now = event.timestamp();
                long frequency = old == null
                        ? 1 : decay(old.frequency(),
                        old.lastAccessTime(), now) + 1;
                IndexedKey updated = new IndexedKey(
                        event.key(), frequency, now,
                        event.sizeBytes());
                byKey.put(key, updated);
                index.add(updated);
            }
        }

        EvictionCandidate candidate() {
            synchronized (lock) {
                if (index.isEmpty()) {
                    return null;
                }
                IndexedKey first = index.first();
                return new EvictionCandidate(first.key(),
                        first.frequency(), first.lastAccessTime(),
                        first.sizeBytes(), first.frequency());
            }
        }

        private static long decay(long frequency,
                                  long lastAccessTime, long now) {
            long elapsed = now - lastAccessTime;
            if (elapsed > DECAY_INTERVAL_MILLIS) {
                return Math.max(0, frequency / 2);
            }
            return frequency;
        }
    }

    private record IndexedKey(byte[] key, long frequency,
                              long lastAccessTime, int sizeBytes) {
    }
}
