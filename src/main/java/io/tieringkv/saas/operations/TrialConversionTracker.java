package io.tieringkv.saas.operations;

import java.util.concurrent.atomic.AtomicLong;

/** 试用转化跟踪（ADR-0155）：转化率 = 转化 / 已结束试用。 */
public final class TrialConversionTracker {

    private final AtomicLong trials = new AtomicLong();
    private final AtomicLong converted = new AtomicLong();
    private final AtomicLong expired = new AtomicLong();

    public void startTrial() {
        trials.incrementAndGet();
    }

    public void markConverted() {
        converted.incrementAndGet();
    }

    public void markExpired() {
        expired.incrementAndGet();
    }

    public double conversionRate() {
        long ended = converted.get() + expired.get();
        return ended == 0 ? 0 : (double) converted.get() / ended;
    }

    public long trialCount() {
        return trials.get();
    }

    public long convertedCount() {
        return converted.get();
    }

    public long expiredCount() {
        return expired.get();
    }
}
