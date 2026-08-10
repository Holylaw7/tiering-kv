package io.tieringkv.storage.cache;

/** 单键热度数据（ADR-0010）：key / frequency / lastAccess / create / size / decay。 */
public final class HotnessEntry {

    private final byte[] key;
    private final long createTime;
    private final FrequencyCounter counter;
    private volatile long lastAccessTime;
    private volatile int sizeBytes;

    HotnessEntry(byte[] key, long nowMillis, long decayIntervalMillis) {
        this.key = key.clone();
        this.createTime = nowMillis;
        this.lastAccessTime = nowMillis;
        this.counter = new FrequencyCounter(decayIntervalMillis, nowMillis);
    }

    public byte[] key() {
        return key;
    }

    public long frequency() {
        return counter.frequency();
    }

    public long lastAccessTime() {
        return lastAccessTime;
    }

    public long createTime() {
        return createTime;
    }

    public int sizeBytes() {
        return sizeBytes;
    }

    public long lastDecayTime() {
        return counter.lastDecayTime();
    }

    FrequencyCounter counter() {
        return counter;
    }

    void touch(long nowMillis) {
        this.lastAccessTime = nowMillis;
    }

    void setSizeBytes(int sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
}
