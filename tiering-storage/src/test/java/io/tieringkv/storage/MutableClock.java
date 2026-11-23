package io.tieringkv.storage;

import io.tieringkv.storage.memory.TimeSource;

/** 测试用可控时钟。 */
public final class MutableClock implements TimeSource {

    private long nowMillis;

    public MutableClock(long initialMillis) {
        this.nowMillis = initialMillis;
    }

    public void advance(long deltaMillis) {
        nowMillis += deltaMillis;
    }

    @Override
    public long nowMillis() {
        return nowMillis;
    }
}
