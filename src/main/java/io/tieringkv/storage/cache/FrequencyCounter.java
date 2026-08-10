package io.tieringkv.storage.cache;

/**
 * 频率计数 + 周期衰减（ADR-0011）：
 * 每经过一个衰减周期，频率右移 1 位（×0.5）；懒计算，不依赖全局扫描。
 */
public final class FrequencyCounter {

    private static final long MAX_FREQUENCY = 1L << 40;

    private final long decayIntervalMillis;
    private volatile long frequency;
    private volatile long lastDecayTime;

    public FrequencyCounter(long decayIntervalMillis, long nowMillis) {
        this.decayIntervalMillis = decayIntervalMillis;
        this.lastDecayTime = nowMillis;
    }

    /** 先衰减、后 +1；返回新频率。 */
    public synchronized long incrementAndDecay(long nowMillis) {
        decay(nowMillis);
        frequency = Math.min(frequency + 1, MAX_FREQUENCY);
        return frequency;
    }

    /** 按已过周期折算衰减；时钟回拨时跳过（单调安全）。 */
    public synchronized void decay(long nowMillis) {
        if (nowMillis <= lastDecayTime) {
            return;
        }
        long elapsed = nowMillis - lastDecayTime;
        long periods = elapsed / decayIntervalMillis;
        if (periods > 0) {
            int shift = (int) Math.min(periods, 63);
            frequency = Math.max(0, frequency >> shift);
            lastDecayTime += periods * decayIntervalMillis;
        }
    }

    public long frequency() {
        return frequency;
    }

    public long lastDecayTime() {
        return lastDecayTime;
    }
}
