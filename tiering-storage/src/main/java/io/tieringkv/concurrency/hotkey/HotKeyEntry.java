package io.tieringkv.concurrency.hotkey;

/** 单键访问计数（ADR-0025）：窗口 + 计数（不可变，随窗重置）。 */
final class HotKeyEntry {

    private final long window;
    private final long count;

    HotKeyEntry(long window, long count) {
        this.window = window;
        this.count = count;
    }

    long window() {
        return window;
    }

    long count() {
        return count;
    }

    HotKeyEntry increment() {
        return new HotKeyEntry(window, count + 1);
    }
}
