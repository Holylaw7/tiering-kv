package io.tieringkv.saas.operations;

import java.util.concurrent.atomic.AtomicLong;

/** 流失检测（ADR-0155）：流失率 = 流失 / (流失 + 续费)。 */
public final class ChurnDetector {

    private final AtomicLong churned = new AtomicLong();
    private final AtomicLong renewed = new AtomicLong();

    public void recordChurn() {
        churned.incrementAndGet();
    }

    public void recordRenewal() {
        renewed.incrementAndGet();
    }

    public double churnRate() {
        long total = churned.get() + renewed.get();
        return total == 0 ? 0 : (double) churned.get() / total;
    }

    public long churnedCount() {
        return churned.get();
    }

    public long renewedCount() {
        return renewed.get();
    }
}
