package io.tieringkv.security.network;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 学习型加固（ADR-0197）：风险结果反馈 → 阈值自进化。 */
public final class LearnedHardener {

    /** 阈值调整审计记录。 */
    public record ThresholdAdjustment(int before, int after,
                                      String reason) {
    }

    private final int minThreshold;
    private final int maxThreshold;
    private final int step;
    private final List<ThresholdAdjustment> audit =
            new CopyOnWriteArrayList<>();
    private int threshold;

    public LearnedHardener(int initialThreshold, int minThreshold,
                           int maxThreshold, int step) {
        if (minThreshold < 0 || maxThreshold < minThreshold
                || step < 1) {
            throw new IllegalArgumentException(
                    "invalid threshold bounds");
        }
        this.threshold = Math.max(minThreshold,
                Math.min(maxThreshold, initialThreshold));
        this.minThreshold = minThreshold;
        this.maxThreshold = maxThreshold;
        this.step = step;
    }

    /** 反馈：真实风险高 → 降低阈值（更早加固）；低 → 提高。 */
    public synchronized int learn(boolean highRiskObserved) {
        int before = threshold;
        if (highRiskObserved) {
            threshold = Math.max(minThreshold,
                    threshold - step);
            audit.add(new ThresholdAdjustment(before, threshold,
                    "high risk observed"));
        } else {
            threshold = Math.min(maxThreshold,
                    threshold + step);
            audit.add(new ThresholdAdjustment(before, threshold,
                    "low risk observed"));
        }
        return threshold;
    }

    public synchronized int threshold() {
        return threshold;
    }

    public List<ThresholdAdjustment> audit() {
        return List.copyOf(audit);
    }
}
