package io.tieringkv.storage.cache;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热度跟踪表（ADR-0010）：key → HotnessEntry，按 key 原子更新。
 * DELETE / EVICT 事件移除条目；decayAll 提供全量清扫入口。
 */
public final class HotnessTracker {

    private final long decayIntervalMillis;
    private final ConcurrentHashMap<ByteBuffer, HotnessEntry> entries = new ConcurrentHashMap<>();

    public HotnessTracker(long decayIntervalMillis) {
        this.decayIntervalMillis = decayIntervalMillis;
    }

    /** 记录事件并返回（更新后的）热度条目；DELETE/EVICT 返回 null。 */
    public HotnessEntry record(AccessEvent event) {
        if (event.operation() == AccessEvent.AccessOperation.DELETE
                || event.operation() == AccessEvent.AccessOperation.EVICT) {
            entries.remove(ByteBuffer.wrap(event.key()));
            return null;
        }
        return entries.compute(ByteBuffer.wrap(event.key()), (key, existing) -> {
            long now = event.timestamp();
            HotnessEntry entry = existing != null
                    ? existing
                    : new HotnessEntry(event.key(), now, decayIntervalMillis);
            entry.counter().incrementAndDecay(now);
            entry.touch(now);
            if (event.sizeBytes() > 0) {
                entry.setSizeBytes(event.sizeBytes());
            }
            return entry;
        });
    }

    public HotnessEntry get(byte[] key) {
        return entries.get(ByteBuffer.wrap(key));
    }

    public int size() {
        return entries.size();
    }

    /** 全量衰减（后台/测试用；热路径采用懒衰减）。 */
    public void decayAll(long nowMillis) {
        for (HotnessEntry entry : entries.values()) {
            entry.counter().decay(nowMillis);
        }
    }
}
