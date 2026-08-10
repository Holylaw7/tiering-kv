package io.tieringkv.concurrency.hotkey;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 热点检测（ADR-0025）：窗口频率 ≥ 阈值标记热点；写失效移除。 */
public final class HotKeyDetector {

    private final AccessCounter counter = new AccessCounter();
    private final HotKeyPolicy policy;
    private final Set<ByteBuffer> hotKeys = ConcurrentHashMap.newKeySet();

    public HotKeyDetector(HotKeyPolicy policy) {
        this.policy = policy;
    }

    /** 记录访问并返回是否热点。 */
    public boolean recordAndCheck(byte[] key, long nowMillis) {
        long count = counter.record(key, nowMillis, policy.windowMillis());
        if (count >= policy.hotThreshold()) {
            hotKeys.add(ByteBuffer.wrap(key));
            if (hotKeys.size() > policy.maxHotKeys()) {
                hotKeys.clear(); // 简单裁剪：超限清空重建
            }
        }
        return hotKeys.contains(ByteBuffer.wrap(key));
    }

    public boolean isHot(byte[] key) {
        return hotKeys.contains(ByteBuffer.wrap(key));
    }

    public void invalidate(byte[] key) {
        hotKeys.remove(ByteBuffer.wrap(key));
        counter.reset(key);
    }

    public int hotKeyCount() {
        return hotKeys.size();
    }
}
