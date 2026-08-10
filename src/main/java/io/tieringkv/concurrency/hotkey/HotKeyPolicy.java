package io.tieringkv.concurrency.hotkey;

/** 热点键策略（ADR-0025）：窗口、阈值、上限与缓存 TTL。 */
public record HotKeyPolicy(
        long windowMillis,
        long hotThreshold,
        int maxHotKeys,
        long cacheTtlMillis) {

    public static HotKeyPolicy defaults() {
        return new HotKeyPolicy(1000, 1000, 1024, 500);
    }
}
