package io.tieringkv.concurrency.hotkey;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;

/** 时间窗访问计数（ADR-0025）：跨窗自动重置。 */
public final class AccessCounter {

    private final ConcurrentHashMap<ByteBuffer, HotKeyEntry> counters = new ConcurrentHashMap<>();

    /** 记录访问并返回当前窗口计数。 */
    public long record(byte[] key, long nowMillis, long windowMillis) {
        long window = nowMillis / windowMillis;
        return counters.compute(ByteBuffer.wrap(key), (ignored, entry) -> {
            if (entry == null || entry.window() != window) {
                return new HotKeyEntry(window, 1);
            }
            return entry.increment();
        }).count();
    }

    public long count(byte[] key) {
        HotKeyEntry entry = counters.get(ByteBuffer.wrap(key));
        return entry == null ? 0 : entry.count();
    }

    public void reset(byte[] key) {
        counters.remove(ByteBuffer.wrap(key));
    }
}
